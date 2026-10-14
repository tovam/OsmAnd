package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightMapRenderLease
import org.junit.Assert.assertEquals
import org.junit.Test

class FlightMapRenderLeaseTest {
    private class Renderer {
        var pauses = 0
        var resumes = 0
    }

    private fun lease() = FlightMapRenderLease<Renderer>({ it.pauses++ }, { it.resumes++ })

    @Test
    fun scrollingAnObscuringPageDoesNotRestartOrRepeatedlyPauseTheMap() {
        val renderer = Renderer()
        val lease = lease()
        repeat(100) { lease.update(renderer, visible = false) }
        assertEquals(1, renderer.pauses)
        assertEquals(0, renderer.resumes)
        repeat(100) { lease.update(renderer, visible = true) }
        assertEquals(1, renderer.resumes)
    }

    @Test
    fun returningToTheNormalMapReleasesThePauseExactlyOnce() {
        val renderer = Renderer()
        val lease = lease()
        lease.update(renderer, visible = false)
        repeat(2) { lease.release(renderer, resumeRendering = true) }
        assertEquals(1, renderer.resumes)
    }

    @Test
    fun destroyingAReplacedRendererNeverResumesTheDetachedSurface() {
        val previous = Renderer()
        val current = Renderer()
        val lease = lease()
        lease.update(previous, visible = false)
        lease.update(current, visible = false)
        lease.release(current, resumeRendering = true)
        assertEquals(0, previous.resumes)
        assertEquals(1, current.pauses)
        assertEquals(1, current.resumes)
    }

    @Test
    fun leavingWhileTheActivityStopsDoesNotRestartRenderingInTheBackground() {
        val renderer = Renderer()
        val lease = lease()
        lease.update(renderer, visible = false)
        lease.release(renderer, resumeRendering = false)
        assertEquals(0, renderer.resumes)
        // The activity has resumed its surface; the returning library must pause it anew.
        lease.update(renderer, visible = false)
        assertEquals(2, renderer.pauses)
        lease.update(renderer, visible = true)
        assertEquals(1, renderer.resumes)
    }

    @Test
    fun releaseWithoutAnAttachedSurfaceCannotResumeAnObsoleteRenderer() {
        val renderer = Renderer()
        val lease = lease()
        lease.update(renderer, visible = false)
        lease.release(null, resumeRendering = true)
        assertEquals(0, renderer.resumes)
    }
}
