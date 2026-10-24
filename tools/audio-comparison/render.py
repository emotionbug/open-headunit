#!/usr/bin/env python3
"""Render actual JNI engines and the isolated production OHU splice into a local listening set.

Requires a JDK, the project's cached Kotlin compiler, Python numpy, and caller-supplied
48 kHz stereo PCM16 WAVs. No recordings or third-party DSP binaries are downloaded.
"""
import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import wave
import zipfile

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
LABELS = {
    'ohu': 'OHU (production splice, isolated)',
    'soundtouch': 'Previous SoundTouch', 'r3': 'Previous R3', 'r3-short': 'Previous R3 Short',
    'wsola': 'Independent WSOLA', 'pv': 'Independent Phase Vocoder',
    'pv-short': 'Independent Phase Vocoder Short', 'gradient': 'Independent Phase Gradient',
}
DEFAULT_INPUTS = ['ebu_castanets', 'ebu_piano_music', 'ebu_soprano', 'ebu_pop', 'ebu_orchestra',
                  'ebu_speech', 'ebu_piano_quiet', 'tones', 'bass', 'impulses', 'antiphase',
                  'independent_stereo', 'quiet_tones', 'silence']
SCENARIOS = ('unity', 'constant', 'toggle', 'matched')
WIDE_SCENARIOS = ('rate-0.5', 'rate-0.8', 'rate-1.05', 'rate-1.1', 'rate-1.25',
                  'rate-1.5', 'rate-2', 'wide-toggle')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def wav(path, pcm):
    with wave.open(str(path), 'wb') as f:
        f.setparams((2, 2, 48000, 0, 'NONE', 'not compressed'))
        f.writeframes(pcm.astype('<i2').tobytes())


def kotlin_command(build):
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
    def jar(group, name, version):
        matches = sorted(p for p in (cache / group / name).glob(version + '/*/*.jar')
                         if p.name == name + '-' + p.parent.parent.name + '.jar')
        if not matches:
            raise RuntimeError(f'Missing {name}; run Gradle unit tests to populate the compiler cache')
        return matches[0]
    version = re.search(r'kotlin-gradle-plugin:([0-9.]+)', (ROOT / 'build.gradle.kts').read_text()).group(1)
    std = jar('org.jetbrains.kotlin', 'kotlin-stdlib', version)
    annotations = jar('org.jetbrains', 'annotations', '13.0')
    compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', version), std, annotations,
                jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                jar('org.jetbrains.kotlin', 'kotlin-script-runtime', version),
                jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')]
    target = build / 'ohu.jar'
    audio = ROOT / 'app/src/main/java/com/andrerinas/openheadunit/decoder/audio'
    subprocess.run(['java', '-cp', os.pathsep.join(map(str, compiler)),
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', os.pathsep.join(map(str, (std, annotations))), '-d', str(target),
                    str(HERE / 'OhuRender.kt'), str(audio / 'PcmOverlap.kt'),
                    str(audio / 'OptimizedPcmOverlap.kt')], check=True)
    return ['java', '-cp', str(target) + os.pathsep + str(std),
            'com.andrerinas.openheadunit.decoder.audio.OhuRenderKt']


def signal_metrics(source, output, name, scenario):
    result = {'peak_pcm16': int(np.max(np.abs(output.astype(np.int32)))),
              'clipped_samples': int(np.count_nonzero((output == 32767) | (output == -32768)))}
    if scenario == 'unity' and len(source) == len(output):
        delta = output.astype(np.float64) - source.astype(np.float64)
        result['unity_max_error_lsb'] = int(np.max(np.abs(delta)))
        result['unity_rms_error_lsb'] = float(np.sqrt(np.mean(delta * delta)))
    if name == 'antiphase':
        result['antiphase_sum_max_lsb'] = int(np.max(np.abs(output.astype(np.int32).sum(axis=1))))
    if name in ('tones', 'bass', 'independent_stereo', 'quiet_tones') and len(output) > 5 * 48000:
        signal = output[48000:5 * 48000].astype(np.float64)
        spec = np.abs(np.fft.rfft(signal * np.hanning(len(signal))[:, None], axis=0)) ** 2
        freq = np.fft.rfftfreq(len(signal), 1 / 48000)
        targets = {'tones': [(0, 440), (0, 5000)], 'bass': [(0, 55), (0, 110)],
                   'independent_stereo': [(0, 440), (1, 5000)], 'quiet_tones': [(0, 440), (0, 3500)]}[name]
        for channel, target in targets:
            band = np.flatnonzero(np.abs(freq - target) < max(10, target * .03))
            peak = band[np.argmax(spec[band, channel])]
            result[f'channel{channel}_tone{target}_peak_hz'] = float(freq[peak])
            narrow = np.abs(freq - target) <= 1.0
            wide = np.abs(freq - target) <= max(20, target * .02)
            result[f'channel{channel}_tone{target}_nearby_sideband_fraction'] = float(
                max(0, spec[wide, channel].sum() - spec[narrow, channel].sum()) /
                max(1e-30, spec[wide, channel].sum()))
    return result


def make_page(out, rows, manifest):
    engine_order = {name: index for index, name in enumerate(LABELS)}
    groups = []
    for name in manifest['inputs']:
        for scenario in ('matched', 'toggle') + WIDE_SCENARIOS:
            group = [r for r in rows if r['input'] == name and r['scenario'] == scenario]
            if not group:
                continue
            group.sort(key=lambda row: engine_order[row['engine']])
            cards = []
            for row in group:
                name_label = html.escape(LABELS[row['engine']])
                cpu = row['thread_cpu_ms_median'] / (row['input_frames'] / 48000)
                p99 = row['block_wall_p99_us_median'] / 1000
                cost = f'CPU {cpu:.2f} ms/입력 1초 · 블록 p99 {p99:.3f} ms'
                if row['engine'] == 'ohu':
                    cost += ' · 접합 코어만 측정 · 첫 출력 미측정'
                elif row.get('first_output_after_frames', -1) >= 0:
                    cost += f' · 첫 출력 공급량 {row["first_output_after_frames"] / 48:g} ms'
                else:
                    cost += ' · Stop 이후 첫 출력'
                cards.append(f'<div class="sample"><div><strong>{name_label}</strong> '
                             f'<span class="cost">({cost})</span></div>'
                             f'<audio aria-label="{name_label}" controls preload="none" src="{row["clip"]}"></audio></div>')
            detail = ('OHU가 실제 소비한 평균 보정량을 기준으로 다른 엔진의 요청 속도를 맞춤. '
                      '1.01 상한·기존 R3 종료 패딩 때문에 최종 길이는 다를 수 있음.' if scenario == 'matched' else
                      '입력 시간 100ms마다 1.00 / 1.01 교대. OHU는 자체 보정 간격을 유지하므로 실제 보정량이 다름.' if scenario == 'toggle' else
                      '입력 시간 100ms마다 0.5 / 2.0 교대. 극단 전환 스트레스 조건이며, 엔진 내부 버퍼에 속도 변경이 적용되는 방식 때문에 실제 출력 길이는 서로 다릅니다. 같은 평균 속도의 음질 순위로 해석하지 마세요.' if scenario == 'wide-toggle' else
                      f'요청 재생 속도 {scenario[5:]}×. 기존 엔진은 보관한 어댑터의 허용 범위만 확장한 연구용 빌드이며 앱 동작이 아님.')
            groups.append(f'<section data-input="{html.escape(name, quote=True)}" data-profile="{scenario}"><h2>{html.escape(name)} / {scenario}</h2><p>{detail}</p>'
                          f'<div class="sample"><div>원본 (처리 전 기준 음원)</div><audio aria-label="원본" controls preload="none" src="clips/{name}__reference.wav"></audio></div>'
                          + ''.join(cards) + '</section>')
    table = []
    for engine in LABELS:
        measured = [r for r in rows if r['engine'] == engine and r['scenario'] == 'constant']
        if not measured:
            continue
        cpu = np.median([r['thread_cpu_ms_median'] / (r['input_frames'] / 48000) for r in measured])
        p99 = np.median([r['block_wall_p99_us_median'] for r in measured])
        look = sorted({r['first_output_after_frames'] / 48 for r in measured if 'first_output_after_frames' in r})
        latency = ', '.join(f'{x:g}' for x in look) if look else '별도 코어 실행; 미측정'
        table.append(f'<tr><td>{LABELS[engine]}</td><td>{cpu:.3f}</td><td>{p99:.2f}</td><td>{latency}</td></tr>')
    page = '''<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>OHU 독자 구현 오디오 비교</title><link rel="icon" href="data:,"><style>
body{font:16px/1.6 system-ui,sans-serif;margin:32px auto;padding:0 20px;max-width:1100px;background:#f5f6f8;color:#182332}
section{background:white;border:1px solid #d7dce2;border-radius:10px;padding:20px;margin:24px 0}label{display:block;margin:12px 0}.sample{margin:20px 0}.cost{color:#46566b}audio{display:block;margin-top:6px;width:100%;max-width:850px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:8px;text-align:left}a{color:#0756a5}</style>
<h1>OHU 독자 구현 오디오 비교</h1>
<p>기본 OHU · 기존 SoundTouch/R3 · 독자 WSOLA/Phase Vocoder/Phase Gradient</p>
<p>같은 48kHz 스테레오 PCM16 입력에 공통 -6.02dB 여유를 적용했습니다. 출력별 음량 정규화는 하지 않았습니다.
엔진명 옆 괄호에는 해당 음원·속도 조건의 PC 실측값을 표시합니다. CPU는 입력 1초당 사용한 스레드 CPU 시간이며, 메모리 사용량은 측정하지 않았습니다.
1–7초 발췌 구간을 비교합니다. 엔진명은 고정 순서로 표시하며 블라인드 시험이나 청취 점수는 아닙니다.</p>
<p><a href="comparison-audio.zip">WAV·보고서 ZIP</a> · <a href="metrics.json">전체 측정 JSON</a> · <a href="manifest.json">입력·코드·바이너리 출처</a></p>
<h2>PC 측정 — Android 성능이 아님</h2><p>12초 입력을 한 번 예열 후 3회 측정. 표는 입력별 중앙값의 중앙값입니다.
CPU 시간에는 JNI 블록 전달·출력 수집이 포함되며 파일 I/O·엔진 생성은 제외됩니다. OHU 행은 전체 믹서가 아닌 파형 선택·접합 코어이므로 절대 비용을 동등한 전체 파이프라인 비용으로 비교하지 마세요.
입력 선행량은 첫 PCM 출력에 필요한 공급량이며 오디오 장치 지연이 아닙니다.</p>
<table><tr><th>엔진</th><th>CPU ms / 입력 1초</th><th>블록 벽시계 p99 µs</th><th>첫 출력 공급량 ms</th></tr>'''
    input_options = ''.join(f'<option value="{html.escape(name, quote=True)}">{html.escape(name)}</option>' for name in manifest['inputs'])
    profile_options = ''.join(f'<option>{name}</option>' for name in ('matched', 'toggle') + WIDE_SCENARIOS)
    page += ''.join(table) + '</table>'
    page += f'<h2>청취 조건</h2><label for="input-filter">음원</label><select id="input-filter"><option value="">전체</option>{input_options}</select>'
    page += f'<label for="profile-filter">속도 조건</label><select id="profile-filter"><option value="">전체</option>{profile_options}</select>'
    page += ''.join(groups)
    page += '''<section><h2>전체 파일과 해석 한계</h2><p>이름이 붙은 전체 길이 WAV는 ZIP의 full/에 있습니다. unity는 무보정, constant는 1% 연속 보정,
toggle은 100ms 간격 전환, matched는 OHU의 평균 소비량에 맞춘 요청입니다. 모든 엔진의 종료 출력을 보존했습니다.
rate-*는 넓은 속도 비교이며 기본 OHU 코어는 그 범위를 지원하지 않아 포함하지 않았습니다. wide-toggle은 극단적인 속도 전환 검사입니다.
앱의 자동 보정 정책은 변경하지 않았습니다. 넓은 범위의 이전 라이브러리는 어댑터 검사만 0.5–2.0으로 확장한 별도 연구용 빌드입니다.
네트워크·디코더·출력 드라이버·실차 CPU·주관적 음질 우열은 이 비교로 증명되지 않습니다. 파형이 달라지는 처리에 단순 PCM 오차나 SNR을 음질 순위로 쓰지 않았습니다.</p>
<p>음악 원본: <a href="https://qc.ebu.io/testmaterials/523/">EBU SQAM</a>, 이전 Codex 연구의 동일 입력. 개인적인 연구·개발 청취 자료이며 앱·저장소에 음원이나 이전 라이브러리를 포함하지 않습니다.</p></section>
<script>
document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>{document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})}));
function filter(){const input=document.getElementById('input-filter').value,profile=document.getElementById('profile-filter').value;document.querySelectorAll('section[data-input]').forEach(s=>{s.hidden=!!((input&&s.dataset.input!==input)||(profile&&s.dataset.profile!==profile));if(s.hidden)s.querySelectorAll('audio').forEach(a=>a.pause())})}
document.querySelectorAll('select').forEach(s=>s.addEventListener('change',filter));
</script></html>'''
    (out / 'index.html').write_text(page)
    (out / 'answer-key.json').unlink(missing_ok=True)
    with zipfile.ZipFile(out / 'comparison-audio.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for pattern in ('index.html', 'metrics.json', 'manifest.json', 'rendering-sources.zip', 'clips/*.wav', 'full/*.wav'):
            for path in out.glob(pattern):
                archive.write(path, path.relative_to(out))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline-lib', type=Path, required=True)
    parser.add_argument('--new-lib', type=Path, default=ROOT / 'build/native-time-stretch/libhur_time_stretch.so')
    parser.add_argument('--stage', choices=['baseline', 'independent', 'all'], default='all')
    parser.add_argument('--names', nargs='+', default=DEFAULT_INPUTS)
    parser.add_argument('--profiles', nargs='+', choices=SCENARIOS + WIDE_SCENARIOS, default=list(SCENARIOS))
    args = parser.parse_args()
    out = args.output.resolve()
    for subdir in ('classes', 'pcm', 'full', 'clips', 'inputs'):
        (out / subdir).mkdir(parents=True, exist_ok=True)
    subprocess.run(['javac', '-d', str(out / 'classes'), str(HERE / 'NativeTimeStretch.java')], check=True)
    ohu = kotlin_command(out / 'classes') if args.stage != 'independent' else None
    rows = json.loads((out / 'metrics.json').read_text()) if (out / 'metrics.json').exists() else []
    manifest = {'host': platform.platform(), 'inputs': {}, 'libraries': {}, 'drivers': {}}
    if (out / 'manifest.json').exists():
        manifest = json.loads((out / 'manifest.json').read_text())
    # Preserve provenance of earlier stages before recording the current drivers.
    for row in rows:
        if 'driver_sha256' not in row:
            row['driver_sha256'] = dict(manifest['drivers'])
        if 'library_sha256' not in row:
            kind = 'baseline' if row['engine'] in ('soundtouch', 'r3', 'r3-short') else 'independent'
            row['library_sha256'] = None if row['engine'] == 'ohu' else manifest['libraries'].get(kind, {}).get('sha256')
    driver_hashes = {name: digest(HERE / name) for name in ('NativeTimeStretch.java', 'OhuRender.kt', 'render.py')}
    manifest['drivers'].update(driver_hashes)
    for kind, lib in [('baseline', args.baseline_lib), ('independent', args.new_lib)]:
        if lib.is_file():
            manifest['libraries'][digest(lib)] = {'kind': kind, 'path': str(lib.resolve()), 'sha256': digest(lib)}
    for name in args.names:
        path = args.inputs / (name + '.wav')
        with wave.open(str(path), 'rb') as f:
            if (f.getnchannels(), f.getsampwidth(), f.getframerate()) != (2, 2, 48000):
                raise ValueError(f'{path}: requires stereo PCM16 at 48 kHz')
            source = np.frombuffer(f.readframes(f.getnframes()), dtype='<i2').reshape(-1, 2)
        source = np.rint(source.astype(np.float64) * .5).astype('<i2')
        raw = out / 'inputs' / (name + '.s16le')
        raw.write_bytes(source.tobytes())
        wav(out / 'full' / (name + '__reference.wav'), source)
        wav(out / 'clips' / (name + '__reference.wav'), source[48000:7 * 48000])
        manifest['inputs'][name] = {'original': str(path.resolve()), 'sha256': digest(path),
                                    'supplied_pcm_sha256': digest(raw), 'gain': .5, 'frames': len(source)}
        engines = []
        if args.stage != 'independent':
            engines += [('ohu', 0, None), ('soundtouch', 1, args.baseline_lib),
                        ('r3', 2, args.baseline_lib), ('r3-short', 3, args.baseline_lib)]
        if args.stage != 'baseline':
            engines += [('wsola', 1, args.new_lib), ('pv', 2, args.new_lib),
                        ('pv-short', 3, args.new_lib), ('gradient', 4, args.new_lib)]
        for engine, identity, lib in engines:
            for scenario in args.profiles:
                if engine == 'ohu' and scenario in WIDE_SCENARIOS:
                    continue
                stem = f'{name}__{engine}__{scenario}'
                target = out / 'pcm' / (stem + '.s16le')
                if engine == 'ohu' and scenario == 'matched':
                    original = next(r for r in rows if r['input'] == name and r['engine'] == engine and r['scenario'] == 'constant')
                    row = dict(original)
                    shutil.copyfile(out / 'full' / f'{name}__ohu__constant.wav', out / 'full' / (stem + '.wav'))
                    shutil.copyfile(out / 'clips' / f'{name}__ohu__constant.wav', out / 'clips' / (stem + '.wav'))
                else:
                    request = scenario
                    if scenario.startswith('rate-'):
                        request = 'matched:' + scenario[5:]
                    if scenario == 'matched':
                        control = next(r for r in rows if r['input'] == name and r['engine'] == 'ohu' and r['scenario'] == 'constant')
                        speed = min(1.01, max(1.0, control['input_frames'] / control['output_frames']))
                        request = 'matched:' + format(speed, '.14g')
                    if engine == 'ohu':
                        command = ohu + [str(raw), str(target), request]
                    else:
                        command = ['java', '-Djava.library.path=' + str(lib.resolve().parent),
                                   '-cp', str(out / 'classes'),
                                   'com.andrerinas.openheadunit.decoder.audio.NativeTimeStretch',
                                   str(identity), str(raw), str(target), request]
                    execution = subprocess.run(command, check=True, text=True, capture_output=True, timeout=120)
                    row = json.loads(execution.stdout)
                    output = np.fromfile(target, dtype='<i2').reshape(-1, 2)
                    row.update(signal_metrics(source, output, name, scenario))
                    row['requested_scenario'] = request
                    row['output_pcm_sha256'] = digest(target)
                    wav(out / 'full' / (stem + '.wav'), output)
                    wav(out / 'clips' / (stem + '.wav'), output[48000:7 * 48000])
                    target.unlink()
                row.update(input=name, engine=engine, scenario=scenario, clip=f'clips/{stem}.wav',
                           full=f'full/{stem}.wav',
                           library_sha256=digest(lib) if lib else None,
                           driver_sha256={name: driver_hashes[name] for name in
                                          (('OhuRender.kt', 'render.py') if engine == 'ohu' else ('NativeTimeStretch.java', 'render.py'))})
                rows = [r for r in rows if (r['input'], r['engine'], r['scenario']) != (name, engine, scenario)]
                rows.append(row)
                (out / 'metrics.json').write_text(json.dumps(rows, indent=2))
                print(f'{name} {engine} {scenario}: {row["output_frames"]} frames; '
                      f'{row["thread_cpu_ms_median"]:.2f}ms thread CPU', flush=True)
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    make_page(out, rows, manifest)
    print(out / 'index.html', flush=True)


if __name__ == '__main__':
    main()
