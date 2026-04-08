package ai.openclaw.app

data class GatewayProviderConfigSnapshot(
  val hash: String? = null,
  val openAiConfigured: Boolean = false,
  val anthropicConfigured: Boolean = false,
)
