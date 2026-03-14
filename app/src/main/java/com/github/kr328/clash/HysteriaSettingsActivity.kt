package com.github.kr328.clash

import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.HysteriaAccountDesign
import com.github.kr328.clash.design.HysteriaSettingsDesign
import com.github.kr328.clash.design.HysteriaTemplateDesign
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.model.HysteriaAccount
import com.github.kr328.clash.service.model.HysteriaConfig
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

class HysteriaSettingsActivity : BaseActivity<HysteriaSettingsDesign>() {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override suspend fun main() {
        val sStore = ServiceStore(this)
        val activeUuid = resolveTargetProfileUuid(sStore) ?: return finish()

        if (sStore.activeProfile != activeUuid) {
            sStore.activeProfile = activeUuid
        }

        val configFile = importedDir.resolve(activeUuid.toString()).resolve("hysteria.json")
        val config = if (configFile.exists()) {
            try {
                json.decodeFromString(HysteriaConfig.serializer(), configFile.readText())
            } catch (e: Exception) {
                HysteriaConfig()
            }
        } else {
            HysteriaConfig()
        }

        val design = HysteriaSettingsDesign(this, config)
        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    // Ignore global events.
                }
                design.requests.onReceive { request ->
                    try {
                        when (request) {
                            HysteriaSettingsDesign.Request.SaveAndGenerate -> {
                                saveAndGenerate(activeUuid, config)
                            }

                            HysteriaSettingsDesign.Request.AddAccount -> {
                                val newAccount = HysteriaAccount(
                                    id = UUID.randomUUID().toString(),
                                    name = "New Account",
                                    serverIp = "",
                                    serverPortRange = "6000-19999",
                                    password = "",
                                    obfs = "hu``hqb`c"
                                )

                                if (editAccount(newAccount)) {
                                    config.accounts = config.accounts + newAccount
                                    config.activeAccountId = newAccount.id
                                    design.update()
                                }
                            }

                            is HysteriaSettingsDesign.Request.EditAccount -> {
                                if (editAccount(request.account)) {
                                    config.activeAccountId = request.account.id
                                    design.update()
                                } else {
                                    config.accounts = config.accounts.filter { it.id != request.account.id }
                                    if (config.activeAccountId == request.account.id) {
                                        config.activeAccountId = config.accounts.firstOrNull { it.enabled }?.id
                                    }
                                    design.update()
                                }
                            }

                            HysteriaSettingsDesign.Request.EditTemplate -> {
                                editTemplate(config)
                            }

                            is HysteriaSettingsDesign.Request.DeleteAccount -> {
                                config.accounts = config.accounts.filter { it.id != request.account.id }
                                if (config.activeAccountId == request.account.id) {
                                    config.activeAccountId = config.accounts.firstOrNull { it.enabled }?.id
                                }
                                design.update()
                            }
                        }
                    } catch (e: Exception) {
                        design.showExceptionToast(e)
                    }
                }
            }
        }
    }

    private suspend fun editAccount(account: HysteriaAccount): Boolean {
        val accountDesign = HysteriaAccountDesign(this, account)

        pushDesign(accountDesign)

        return try {
            while (isActive) {
                val request = select<HysteriaAccountDesign.Request?> {
                    events.onReceive { null }
                    accountDesign.requests.onReceive { it }
                }

                if (request == HysteriaAccountDesign.Request.Delete) {
                    return false
                }

                if (request == null) {
                    break
                }
            }

            true
        } finally {
            popDesign()
        }
    }

    private suspend fun editTemplate(config: HysteriaConfig) {
        if (config.yamlTemplate.isBlank()) {
            config.yamlTemplate = defaultTemplate(config.localPort)
        }

        val templateDesign = HysteriaTemplateDesign(this, config)

        pushDesign(templateDesign)

        try {
            while (isActive) {
                val request = select<HysteriaTemplateDesign.Request?> {
                    events.onReceive { null }
                    templateDesign.requests.onReceive { it }
                }

                if (request == null) break
            }
        } finally {
            popDesign()
        }
    }
    private suspend fun resolveTargetProfileUuid(store: ServiceStore): UUID? {
        intent.uuid?.let { return it }
        store.activeProfile?.let { return it }

        return runCatching {
            withProfile {
                val current = queryActive() ?: queryAll().firstOrNull()

                if (current != null) {
                    return@withProfile current.uuid
                }

                val created = create(Profile.Type.File, "Hysteria Profile")
                queryByUUID(created)?.let { setActive(it) }
                created
            }
        }.getOrNull()
    }

    private suspend fun saveAndGenerate(uuid: UUID, config: HysteriaConfig) {
        validateConfig(config)

        withContext(Dispatchers.IO) {
            val importedProfile = withProfile { queryByUUID(uuid) }?.imported == true
            val profileDir = if (importedProfile) {
                importedDir.resolve(uuid.toString())
            } else {
                pendingDir.resolve(uuid.toString())
            }
            profileDir.mkdirs()

            val template = if (config.yamlTemplate.isBlank()) {
                defaultTemplate(config.localPort)
            } else {
                config.yamlTemplate
            }

            val activeAccount = resolveActiveAccount(config)
            val generatedYaml = upsertHysteriaProxy(template, config.localPort)
            val yaml = injectHysteriaMeta(generatedYaml, activeAccount)

            if (HYSTERIA_LB_REGEX.containsMatchIn(yaml) && activeAccount != null) {
                config.enabled = true
            }

            profileDir.resolve("hysteria.json")
                .writeText(json.encodeToString(HysteriaConfig.serializer(), config))
            profileDir.resolve("config.yaml").writeText(yaml)

            if (!importedProfile) {
                withProfile { commit(uuid) }
            }

            val name = when {
                activeAccount != null -> "Hysteria: ${activeAccount.name}"
                config.accounts.any { it.enabled } -> "Hysteria-LB (${config.accounts.count { it.enabled }} Accounts)"
                else -> "Hysteria (None Selected)"
            }

            val dao = Database.database.openImportedDao()
            val existing = dao.queryByUUID(uuid)
            if (existing != null) {
                dao.update(existing.copy(name = name))
            } else {
                dao.insert(
                    Imported(
                        uuid = uuid,
                        name = name,
                        type = Profile.Type.File,
                        source = "",
                        interval = 0L,
                        upload = 0L,
                        download = 0L,
                        total = 0L,
                        expire = 0L,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }

            val intent = android.content.Intent(Intents.ACTION_PROFILE_CHANGED)
            intent.putExtra(Intents.EXTRA_UUID, uuid.toString())
            this@HysteriaSettingsActivity.sendBroadcast(intent)

            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }

    private fun resolveActiveAccount(config: HysteriaConfig): HysteriaAccount? {
        val enabledAccounts = config.accounts.filter { it.enabled }
        val selected = enabledAccounts.firstOrNull { it.id == config.activeAccountId }

        if (selected != null) {
            return selected
        }

        val fallback = enabledAccounts.firstOrNull()
        config.activeAccountId = fallback?.id

        return fallback
    }

    private fun validateConfig(config: HysteriaConfig) {
        if (config.localPort !in 1..65535) {
            throw IllegalArgumentException("Invalid local port: ${config.localPort}")
        }

        if (config.recvWindowConn <= 0 || config.recvWindow <= 0) {
            throw IllegalArgumentException("recv-window values must be > 0")
        }

        val normalizedLevel = config.logLevel.trim().lowercase()
        if (normalizedLevel !in setOf("silent", "error", "info", "debug")) {
            throw IllegalArgumentException("Unsupported log level: ${config.logLevel}")
        }
        config.logLevel = normalizedLevel

        config.accounts.filter { it.enabled }.forEach { validateAccount(it) }

        if (config.enabled && config.accounts.none { it.enabled }) {
            throw IllegalArgumentException("Hysteria is enabled but no account is enabled")
        }
    }

    private fun validateAccount(account: HysteriaAccount) {
        if (account.name.isBlank()) {
            throw IllegalArgumentException("Account name cannot be empty")
        }

        val normalizedHost = parseServerHost(account.serverIp)
            ?: throw IllegalArgumentException("Server IP/host cannot be empty for account ${account.name}")

        account.serverIp = normalizedHost

        val match = PORT_RANGE_REGEX.matchEntire(account.serverPortRange.trim())
            ?: throw IllegalArgumentException("Invalid server port/range for account ${account.name}")

        val from = match.groupValues[1].toInt()
        val to = (match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: match.groupValues[1]).toInt()

        if (from !in 1..65535 || to !in 1..65535 || from > to) {
            throw IllegalArgumentException("Invalid server port/range for account ${account.name}")
        }

        account.serverPortRange = if (from == to) "$from" else "$from-$to"
    }

    private fun parseServerHost(raw: String): String? {
        val input = raw.trim()
        if (input.isEmpty()) return null

        if (input.contains("://")) {
            val uri = runCatching { URI(input) }.getOrNull()
                ?: throw IllegalArgumentException("Invalid server address: $raw")
            return uri.host?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Invalid server address: $raw")
        }

        val bracketed = BRACKETED_HOST_REGEX.matchEntire(input)
        if (bracketed != null) {
            return bracketed.groupValues[1].trim().takeIf { it.isNotEmpty() }
        }

        if (input.count { it == ':' } == 1) {
            val maybeHost = input.substringBefore(':').trim()
            val maybePort = input.substringAfter(':').trim()
            if (maybeHost.isNotEmpty() && maybePort.toIntOrNull() in 1..65535) {
                return maybeHost
            }
        }

        return input
    }

    private fun defaultTemplate(localPort: Int): String {
        return """
            mode: rule
            ipv6: false
            log-level: info
            allow-lan: true
            mixed-port: 7890
            unified-delay: true
            tcp-concurrent: true
            external-controller: 127.0.0.1:9090
            
            dns:
              enable: true
              ipv6: false
              enhanced-mode: fake-ip
              fake-ip-range: 198.18.0.1/16
              default-nameserver:
                - 1.1.1.1
                - 8.8.8.8
              nameserver:
                - https://1.1.1.1/dns-query
                - https://1.0.0.1/dns-query
            
            proxies:
              - name: "Hysteria-LB"
                type: socks5
                server: 127.0.0.1
                port: $localPort
                udp: true
            
            proxy-groups:
              - name: "Proxy"
                type: select
                proxies:
                  - "Hysteria-LB"
                  - DIRECT
            
            rules:
              - MATCH,Proxy
        """.trimIndent()
    }

    private fun upsertHysteriaProxy(yaml: String, localPort: Int): String {
        val proxiesHeader = PROXIES_HEADER_REGEX.find(yaml)
        val proxyEntry = "  - name: \"Hysteria-LB\"\n    type: socks5\n    server: 127.0.0.1\n    port: $localPort\n    udp: true"

        if (proxiesHeader == null) {
            return "$yaml\n\nproxies:\n$proxyEntry\n"
        }

        val proxiesBodyStart = proxiesHeader.range.last + 1
        val sectionTail = SECTION_HEADER_REGEX.find(yaml, proxiesBodyStart)
        val proxiesBodyEnd = sectionTail?.range?.first ?: yaml.length
        val proxiesBody = yaml.substring(proxiesBodyStart, proxiesBodyEnd)

        val proxyBlockStart = HYSTERIA_LB_REGEX.find(proxiesBody)

        if (proxyBlockStart != null) {
            val nextProxy = Regex("""(?m)^\s{2}-\s*name:\s*""")
                .find(proxiesBody, proxyBlockStart.range.last + 1)
            val blockEndExclusive = nextProxy?.range?.first ?: proxiesBody.length
            val block = proxiesBody.substring(proxyBlockStart.range.first, blockEndExclusive)
            val withPatchedPort = if (PORT_LINE_REGEX.containsMatchIn(block)) {
                block.replaceFirst(PORT_LINE_REGEX, "$1$localPort")
            } else {
                "${block.trimEnd()}\n    port: $localPort\n"
            }

            val patchedBlock = if (UDP_LINE_REGEX.containsMatchIn(withPatchedPort)) {
                withPatchedPort.replaceFirst(UDP_LINE_REGEX, "$1true")
            } else {
                "${withPatchedPort.trimEnd()}\n    udp: true\n"
            }

            val patchedBody = proxiesBody.substring(0, proxyBlockStart.range.first) +
                patchedBlock +
                proxiesBody.substring(blockEndExclusive)

            return yaml.substring(0, proxiesBodyStart) + patchedBody + yaml.substring(proxiesBodyEnd)
        }

        val separator = if (proxiesBody.isNotBlank() && !proxiesBody.endsWith("\n")) "\n" else ""
        val patchedBody = "$proxyEntry\n$separator$proxiesBody"
        return yaml.substring(0, proxiesBodyStart) + patchedBody + yaml.substring(proxiesBodyEnd)
    }

    private fun injectHysteriaMeta(yaml: String, activeAccount: HysteriaAccount?): String {
        val meta = buildString {
            append("# cfa-hysteria-mode: auto\n")
            append("# cfa-hysteria-lb: Hysteria-LB\n")
            append("# cfa-hysteria-active-id: ${activeAccount?.id ?: "none"}\n")
            append("# cfa-hysteria-active-name: ${activeAccount?.name ?: "none"}\n")
        }

        return "$meta$yaml"
    }

    companion object {
        private val PORT_RANGE_REGEX = Regex("""^(\d{1,5})(?:-(\d{1,5}))?$""")
        private val BRACKETED_HOST_REGEX = Regex("""^\[([^\]]+)](?::(\d{1,5}))?$""")
        private val PORT_LINE_REGEX = Regex("""(?m)^(\s*port:\s*)\d+\s*$""")
        private val UDP_LINE_REGEX = Regex("""(?m)^(\s*udp:\s*)(?:true|false)\s*$""")
        private val PROXIES_HEADER_REGEX = Regex("""(?m)^proxies:\s*$""")
        private val SECTION_HEADER_REGEX = Regex("""(?m)^[a-zA-Z0-9_-]+:\s*$""")
        private val HYSTERIA_LB_REGEX = Regex("""(?m)^\s{2}-\s*name:\s*["']?Hysteria-LB["']?\s*$""")
    }
}
