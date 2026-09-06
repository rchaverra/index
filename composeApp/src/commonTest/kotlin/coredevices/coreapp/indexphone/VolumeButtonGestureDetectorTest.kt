package coredevices.coreapp.indexphone

import coredevices.ring.service.button.RingGesture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VolumeButtonGestureDetectorTest {
    private val timing = VolumeButtonGestureTiming(
        holdThresholdMillis = 600,
        multiClickGapMillis = 600,
    )

    @Test
    fun clickIsEmittedWhenMultiClickWindowCloses() {
        val detector = detector()

        assertEquals(emptyList(), detector.onDown(0))
        assertEquals(emptyList(), detector.onUp(200))
        assertEquals(800, detector.nextDeadlineMillis)
        assertEquals(emptyList(), detector.onTimeAdvanced(799))
        assertEquals(listOf(recognized(RingGesture.Click)), detector.onTimeAdvanced(800))
        assertNull(detector.nextDeadlineMillis)
    }

    @Test
    fun twoShortPressesBecomeDoubleClick() {
        val detector = detector()

        detector.onDown(0)
        detector.onUp(100)
        detector.onDown(300)
        detector.onUp(400)

        assertEquals(listOf(recognized(RingGesture.DoubleClick)), detector.onTimeAdvanced(1_000))
    }

    @Test
    fun thirdShortPressEmitsTripleClickImmediately() {
        val detector = detector()

        detector.onDown(0)
        detector.onUp(80)
        detector.onDown(180)
        detector.onUp(260)
        detector.onDown(360)

        assertEquals(listOf(recognized(RingGesture.TripleClick)), detector.onUp(440))
        assertNull(detector.nextDeadlineMillis)
    }

    @Test
    fun holdIsRecognizedAtThresholdAndReleasedOnUp() {
        val detector = detector()

        detector.onDown(1_000)
        assertEquals(1_600, detector.nextDeadlineMillis)
        assertEquals(listOf(recognized(RingGesture.Hold)), detector.onTimeAdvanced(1_600))
        assertEquals(listOf(released(RingGesture.Hold)), detector.onUp(2_300))
    }

    @Test
    fun shortPressThenHoldBecomesClickHold() {
        val detector = detector()

        detector.onDown(0)
        detector.onUp(100)
        detector.onDown(300)

        assertEquals(listOf(recognized(RingGesture.ClickHold)), detector.onTimeAdvanced(900))
        assertEquals(listOf(released(RingGesture.ClickHold)), detector.onUp(1_400))
    }

    @Test
    fun releaseAtHoldThresholdIsAHold() {
        val detector = detector()

        detector.onDown(0)

        assertEquals(
            listOf(recognized(RingGesture.Hold), released(RingGesture.Hold)),
            detector.onUp(600),
        )
    }

    @Test
    fun newDownAtClickDeadlineStartsANewSequence() {
        val detector = detector()

        detector.onDown(0)
        detector.onUp(100)

        assertEquals(listOf(recognized(RingGesture.Click)), detector.onDown(700))
        detector.onUp(700)
        assertEquals(listOf(recognized(RingGesture.Click)), detector.onTimeAdvanced(1_300))
    }

    @Test
    fun repeatedDownDoesNotCreateAnotherPress() {
        val detector = detector()

        detector.onDown(0)
        assertEquals(emptyList(), detector.onDown(200))
        assertEquals(listOf(recognized(RingGesture.Hold)), detector.onDown(600))
        assertEquals(emptyList(), detector.onDown(800))
        assertEquals(listOf(released(RingGesture.Hold)), detector.onUp(900))
    }

    @Test
    fun upWithoutDownIsIgnored() {
        val detector = detector()

        assertEquals(emptyList(), detector.onUp(100))
        assertNull(detector.nextDeadlineMillis)
    }

    private fun detector() = VolumeButtonGestureDetector(timing)

    private fun recognized(gesture: RingGesture) = VolumeButtonGestureSignal.Recognized(gesture)

    private fun released(gesture: RingGesture) = VolumeButtonGestureSignal.HoldReleased(gesture)
}
