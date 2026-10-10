package app.linkfill

import android.app.Application
import app.linkfill.mesh.MeshController
import app.linkfill.session.LinkSession
import kotlinx.coroutines.MainScope

/** Holds the single LinkSession and MeshController so the activity and the foreground services share them. */
class LinkFillApp : Application() {
    lateinit var session: LinkSession
        private set
    lateinit var mesh: MeshController
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = MainScope()
        session = LinkSession(this, scope)
        mesh = MeshController(this, scope)
    }
}
