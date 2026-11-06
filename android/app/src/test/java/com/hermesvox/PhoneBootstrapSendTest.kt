package com.hermesvox

import com.hermesvox.core.HermesException
import com.hermesvox.core.TestServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * The two host requests the one-button flow makes besides the agent turn:
 *
 *  * `POST /api/jobs/{id}/run` on the active connection, with the same bearer and base path every
 *    other request uses — the app triggers the job the agent created, and nothing else;
 *  * `GET /v1/health` as a boolean probe, because a failed probe after the trigger is the expected
 *    restart, not an exception to interpret.
 *
 * A 404 is a refusal and [PhoneBootstrap.triggerRefused] says so; a transport failure is not, since
 * the gateway may have gone down with the job.
 */
class PhoneBootstrapSendTest {

    private val profile = "/p/bootstrap"
    private val key = "profile-key-9f1c"
    private val jobId = "a1b2c3d4e5f6"

    private fun session(url: String) =
        PhoneSetupStore.build(PhoneSetupSend.Connection(url = url, key = key))

    @Test fun the_trigger_posts_to_the_jobs_run_route_on_the_active_connection() {
        val seen = Collections.synchronizedList(ArrayList<String>())
        val bodies = Collections.synchronizedList(ArrayList<String>())
        TestServer { r, w ->
            synchronized(seen) { seen.add("${r.method} ${r.path}") }
            if (r.method == "POST" && r.path == "$profile/api/jobs/$jobId/run") {
                synchronized(bodies) { bodies.add(r.body) }
                w.send(200, "OK", """{"job":{"id":"$jobId","name":"vox-gateway-restart"}}""")
            } else {
                w.send(404, "Not Found", """{"detail":"no route ${r.path}"}""")
            }
        }.use { srv ->
            session(srv.url + profile).runJob(jobId)
            val request = srv.requests.single()
            assertEquals("the job route under the profile", "$profile/api/jobs/$jobId/run", request.path)
            assertEquals("the same bearer as every turn", "Bearer $key", request.header("Authorization"))
            assertEquals("an empty JSON body is the documented trigger", "{}", request.body)
        }
    }

    @Test fun a_job_the_gateway_does_not_know_is_a_refusal_not_a_restart() {
        TestServer { _, w ->
            w.send(404, "Not Found", """{"error":"Job not found"}""")
        }.use { srv ->
            val error = try {
                session(srv.url + profile).runJob(jobId)
                null
            } catch (t: Throwable) {
                t
            }
            assertTrue("the run must fail", error is HermesException)
            assertTrue("a 404 is a refusal, not an ambiguous transport failure", PhoneBootstrap.triggerRefused(error!!))
        }
    }

    @Test fun a_bad_job_id_is_a_refusal_too() {
        TestServer { _, w ->
            w.send(400, "Bad Request", """{"error":"Invalid job ID format"}""")
        }.use { srv ->
            val error = try {
                session(srv.url + profile).runJob("not-a-job-id")
                null
            } catch (t: Throwable) {
                t
            }
            assertTrue(error is HermesException)
            assertTrue(PhoneBootstrap.triggerRefused(error!!))
        }
    }

    @Test fun a_health_probe_is_true_only_when_the_gateway_answers() {
        TestServer { r, w ->
            if (r.method == "GET" && r.path == "$profile/v1/health") {
                w.send(200, "OK", """{"status":"ok"}""")
            } else {
                w.send(503, "Service Unavailable", "down")
            }
        }.use { srv ->
            assertTrue(session(srv.url + profile).gatewayAnswers(2_000))
        }

        TestServer { _, w -> w.send(503, "Service Unavailable", "down") }.use { srv ->
            assertFalse("a 503 is the drop the flow expects", session(srv.url + profile).gatewayAnswers(2_000))
        }
    }

    @Test fun an_unreachable_gateway_probes_false_instead_of_throwing() {
        assertFalse(
            "a closed port is a probe result, not an exception the UI has to catch",
            session("http://127.0.0.1:1").gatewayAnswers(500),
        )
    }
}
