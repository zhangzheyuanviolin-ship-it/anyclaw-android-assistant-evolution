package com.codex.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class AgentHubActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_hub)

        findViewById<Button>(R.id.btnAgentHubOpenClaw).setOnClickListener {
            startMainWithTarget(MainActivity.OPEN_TARGET_OPENCLAW_SESSION)
        }

        findViewById<Button>(R.id.btnAgentHubCodex).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_TARGET, MainActivity.OPEN_TARGET_CODEX_HOME)
                },
            )
        }

        findViewById<Button>(R.id.btnAgentHubClaude).setOnClickListener {
            startMainWithTarget(MainActivity.OPEN_TARGET_CLAUDE_SESSION)
        }

        findViewById<Button>(R.id.btnAgentHubOpenCode).setOnClickListener {
            startActivity(
                Intent(this, CliAgentChatActivity::class.java).apply {
                    putExtra(CliAgentChatActivity.EXTRA_AGENT_ID, ExternalAgentId.OPEN_CODE.value)
                },
            )
        }

        findViewById<Button>(R.id.btnAgentHubPermissionCenter).setOnClickListener {
            startActivity(Intent(this, PermissionManagerActivity::class.java))
        }
    }

    private fun startMainWithTarget(target: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_TARGET, target)
        }
        startActivity(intent)
    }
}
