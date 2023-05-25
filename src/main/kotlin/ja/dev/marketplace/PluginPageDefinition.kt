package ja.dev.marketplace

interface PluginPageDefinition {
    suspend fun createTemplateParameters(): Map<String, Any?>
}