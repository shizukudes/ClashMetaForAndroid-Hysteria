package com.github.kr328.clash

import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.HysteriaSettingsDesign
import com.github.kr328.clash.design.HysteriaAccountDesign
import com.github.kr328.clash.design.HysteriaTemplateDesign
import com.github.kr328.clash.service.model.HysteriaConfig
import com.github.kr328.clash.service.model.HysteriaAccount
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*

class HysteriaSettingsActivity : BaseActivity<HysteriaSettingsDesign>() {
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override suspend fun main() {
        val sStore = ServiceStore(this)
        val activeUuid = intent.uuid ?: sStore.activeProfile ?: return finish()

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
                    // Handle global events
                }
                design.requests.onReceive { request ->
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
                                design.update()
                            }
                        }
                        is HysteriaSettingsDesign.Request.EditAccount -> {
                            if (editAccount(request.account)) {
                                design.update()
                            } else {
                                config.accounts = config.accounts.filter { it.id != request.account.id }
                                design.update()
                            }
                        }
                        HysteriaSettingsDesign.Request.EditTemplate -> {
                            editTemplate(config)
                        }
                        is HysteriaSettingsDesign.Request.DeleteAccount -> {
                            config.accounts = config.accounts.filter { it.id != request.account.id }
                            design.update()
                        }
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
            config.yamlTemplate = """
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
                    port: ${config.localPort}
                
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

    private suspend fun saveAndGenerate(uuid: UUID, config: HysteriaConfig) {
        withContext(Dispatchers.IO) {
            // 1. Save hysteria.json
            val profileDir = importedDir.resolve(uuid.toString())
            profileDir.mkdirs()
            profileDir.resolve("hysteria.json").writeText(json.encodeToString(HysteriaConfig.serializer(), config))

            // 2. Generate config.yaml
            val enabledAccounts = config.accounts.filter { it.enabled }
            val name = when (enabledAccounts.size) {
                0 -> "Hysteria (None Selected)"
                1 -> "Hysteria: ${enabledAccounts[0].name}"
                else -> "Hysteria: ${enabledAccounts[0].name} (+${enabledAccounts.size - 1})"
            }

            if (config.yamlTemplate.isBlank()) {
                config.yamlTemplate = """
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
                        port: ${config.localPort}
                    
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
            
            var yaml = config.yamlTemplate
            
            if (yaml.contains("name: \"Hysteria-LB\"")) {
                val regex = Regex("""(name:\s*["']Hysteria-LB["'][\s\S]*?port:\s*)\d+""")
                yaml = yaml.replace(regex, "$1${config.localPort}")
            } else {
                val proxyEntry = "  - name: \"Hysteria-LB\"\n    type: socks5\n    server: 127.0.0.1\n    port: ${config.localPort}"
                yaml = yaml.replaceFirst("proxies:", "proxies:\n$proxyEntry")
            }

            profileDir.resolve("config.yaml").writeText(yaml)

            // 3. Update DB
            val dao = com.github.kr328.clash.service.data.Database.database.openImportedDao()
            val existing = dao.queryByUUID(uuid)
            if (existing != null) {
                dao.update(existing.copy(name = name, createdAt = System.currentTimeMillis()))
            }

            // 4. Notify
            val intent = android.content.Intent(Intents.ACTION_PROFILE_CHANGED)
            intent.putExtra(Intents.EXTRA_UUID, uuid.toString())
            this@HysteriaSettingsActivity.sendBroadcast(intent)
            
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }
}
