package com.github.kr328.clash

import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.design.HysteriaSettingsDesign
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.HysteriaStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.*

class HysteriaSettingsActivity : BaseActivity<HysteriaSettingsDesign>() {
    override suspend fun main() {
        val hysteriaStore = HysteriaStore(this)
        val design = HysteriaSettingsDesign(this, hysteriaStore)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    // Handle global events if needed
                }
                design.requests.onReceive {
                    when (it) {
                        HysteriaSettingsDesign.Request.GenerateConfig -> {
                            generateAndActivateProfile(hysteriaStore)
                        }
                    }
                }
            }
        }
    }

    private suspend fun generateAndActivateProfile(hStore: HysteriaStore) {
        withContext(Dispatchers.IO) {
            val uuid = UUID.nameUUIDFromBytes("hysteria-auto".toByteArray())
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val name = "Hysteria Edition ($dateStr)"
            val port = hStore.localPort
            
            val yaml = """
                mode: rule
                ipv6: false
                log-level: info
                allow-lan: true
                mixed-port: 7890
                unified-delay: true
                tcp-concurrent: true
                external-controller: 127.0.0.1:9090
                
                proxies:
                  - name: "Hysteria-LB"
                    type: socks5
                    server: 127.0.0.1
                    port: $port
                
                proxy-groups:
                  - name: "Proxy"
                    type: select
                    proxies:
                      - "Hysteria-LB"
                      - DIRECT
                
                rules:
                  - MATCH,Proxy
            """.trimIndent()

            // 1. Write file
            val profileDir = importedDir.resolve(uuid.toString())
            profileDir.deleteRecursively()
            profileDir.mkdirs()
            profileDir.resolve("config.yaml").outputStream().use {
                it.write(yaml.toByteArray())
            }

            // 2. Add to DB if not exists
            val dao = com.github.kr328.clash.service.data.Database.database.openImportedDao()
            val existing = dao.queryByUUID(uuid)
            if (existing != null) {
                dao.update(
                    existing.copy(
                        name = name,
                        createdAt = System.currentTimeMillis()
                    )
                )
            } else {
                dao.insert(
                    Imported(
                        uuid = uuid,
                        name = name,
                        type = Profile.Type.File,
                        source = "hysteria://auto",
                        interval = 0,
                        upload = 0,
                        download = 0,
                        total = 0,
                        expire = 0,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }

            // 3. Set as active profile
            val srvStore = ServiceStore(this@HysteriaSettingsActivity)
            srvStore.activeProfile = uuid
            
            // 4. Notify change
            val intent = android.content.Intent(Intents.ACTION_PROFILE_CHANGED)
            intent.putExtra(Intents.EXTRA_UUID, uuid.toString())
            this@HysteriaSettingsActivity.sendBroadcast(intent)
            
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }
}
