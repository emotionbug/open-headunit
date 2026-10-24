execute_process(COMMAND "${NM}" -C "${LIBRARY}"
    RESULT_VARIABLE result OUTPUT_VARIABLE symbols ERROR_VARIABLE error)
if(NOT result EQUAL 0)
    message(FATAL_ERROR "Cannot inspect native library: ${error}")
endif()

# Check both defined and unresolved references, without pinning the private names
# or layout of the independent engines. JNI execution checks their availability.
foreach(excluded "RubberBand::" "soundtouch::" " rubberband_" " soundtouch_")
    string(FIND "${symbols}" "${excluded}" position)
    if(NOT position EQUAL -1)
        message(FATAL_ERROR "Removed third-party dependency remains linked: ${excluded}")
    endif()
endforeach()
