package com.vasmarfas.networkhelm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.vasmarfas.networkhelm.ui.sync.SignInReturn

// the browser comes here by the scheme of the app after a sign-in to a cloud: the address goes to the sign-in that waits for it, and the
// task of the app comes to the front as it was, without a new main activity
class SignInActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.dataString?.let(SignInReturn::cameBack)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
