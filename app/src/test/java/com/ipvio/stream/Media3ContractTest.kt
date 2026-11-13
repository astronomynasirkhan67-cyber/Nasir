package com.ipvio.stream

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Only test that imports Media3: guards the mirrored error codes in PlaybackErrorCodes. */
class Media3ContractTest {
    @Test fun errorCodes_matchMedia3() {
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackErrorCodes.IO_NETWORK_CONNECTION_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackErrorCodes.IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackErrorCodes.PARSING_MANIFEST_MALFORMED)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackErrorCodes.PARSING_CONTAINER_UNSUPPORTED)
        assertEquals(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackErrorCodes.DECODER_INIT_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackErrorCodes.DECODING_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackErrorCodes.DECODING_FORMAT_UNSUPPORTED)
    }
}
