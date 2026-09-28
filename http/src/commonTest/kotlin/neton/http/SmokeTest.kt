package neton.http

import kotlin.test.Test
import kotlin.test.assertTrue

class SmokeTest {
    @Test fun httpExceptionIsAnIllegalArgument() = assertTrue(HttpException("x") is IllegalArgumentException)
}
