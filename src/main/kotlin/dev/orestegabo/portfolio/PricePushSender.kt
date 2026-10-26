package dev.orestegabo.portfolio

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.*
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

@Service
class PricePushSender(private val db: JdbcTemplate,private val json: JsonCodec,
    @Value("\${gabo.push.firebase-project:}") private val firebaseProject: String,
    @Value("\${gabo.push.apple-team:}") private val appleTeam: String,
    @Value("\${gabo.push.apple-key-id:}") private val appleKeyId: String,
    @Value("\${gabo.push.apple-key:}") private val appleKey: String,
    @Value("\${gabo.push.apple-topic:dev.orestegabo.portfolio.Portfolio}") private val appleTopic: String) {
    private val http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(5)).build()
    private val firebase by lazy { FirebaseMessaging.getInstance(FirebaseApp.initializeApp(FirebaseOptions.builder()
        .setConnectTimeout(5000).setReadTimeout(8000).setProjectId(firebaseProject).setCredentials(GoogleCredentials.getApplicationDefault()).build(),"portfolio-push")) }
    private var appleJwt=""
    private var appleIssued=0L
    @Synchronized private fun appleToken(): String {
        val now=Instant.now().epochSecond
        if(appleJwt.isNotEmpty() && now-appleIssued<3000)return appleJwt
        fun base(bytes: ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val content=base(json.write(mapOf("alg" to "ES256","kid" to appleKeyId)).toByteArray())+"."+
            base(json.write(mapOf("iss" to appleTeam,"iat" to now)).toByteArray())
        val pem=appleKey.replace("\\n","\n").replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replace(Regex("\\s"),"")
        val key=KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val signature=Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(key);update(content.toByteArray()) }.sign()
        appleJwt=content+"."+base(signature);appleIssued=now;return appleJwt
    }
    fun deliver() {
        val started=System.nanoTime()
        val rows=db.queryForList("""SELECT q.notification_id::text,q.device_id::text,d.platform,d.token,d.environment,d.language,n.ticker
            FROM price_push_deliveries q JOIN price_notifications n ON n.id=q.notification_id
            JOIN push_devices d ON d.id=q.device_id AND d.owner_id=n.owner_id
            WHERE q.sent_at IS NULL AND q.attempts<5 AND q.next_attempt_at<=now() AND n.read_at IS NULL
                AND NOT EXISTS(SELECT 1 FROM bond_users u WHERE u.id=n.owner_id AND u.disabled_at IS NOT NULL) AND n.created_at>=now()-interval '3 days' ORDER BY n.created_at LIMIT 20""")
        for(row in rows) {
            if((System.nanoTime()-started)>45_000_000_000L)break
            val platform=row.text("platform")
            if(platform=="android" && firebaseProject.isBlank() || platform=="ios" && (appleKey.isBlank() || appleTeam.isBlank() || appleKeyId.isBlank()))continue
            val id=row["notification_id"];val device=row["device_id"]
            if(db.update("UPDATE price_push_deliveries SET attempts=attempts+1,next_attempt_at=now()+interval '5 minutes' WHERE notification_id=?::uuid AND device_id=?::uuid AND sent_at IS NULL AND next_attempt_at<=now()",id,device)!=1)continue
            try {
                // No balance, price, holding quantity or account identity on the lock screen.
                val french=row.text("language")=="fr"
                val title=if(french)"Cours des actions" else "Share price update"
                val body=if(french)"Un nouveau cours RSE est disponible. Ouvrez l’application pour voir les détails." else "A new RSE price is available. Open the app to see the details."
                var invalid=false
                val success=if(platform=="android") {
                    try {
                        firebase.send(Message.builder().setToken(row.text("token"))
                            .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                            .putData("route","stock-notifications").putData("notificationId",id.toString())
                            .setAndroidConfig(AndroidConfig.builder().setTtl(86400000).setNotification(AndroidNotification.builder()
                                .setChannelId("price_changes").setTag(id.toString()).setVisibility(AndroidNotification.Visibility.PRIVATE).build()).build()).build())
                        true
                    } catch(e: FirebaseMessagingException) { invalid=e.messagingErrorCode==MessagingErrorCode.UNREGISTERED;false }
                } else {
                    val host=if(row.text("environment")=="sandbox")"api.sandbox.push.apple.com" else "api.push.apple.com"
                    val request=HttpRequest.newBuilder(URI("https://$host/3/device/"+row.text("token"))).timeout(Duration.ofSeconds(8))
                        .header("authorization","bearer "+appleToken()).header("apns-topic",appleTopic).header("apns-push-type","alert")
                        .header("apns-priority","5").header("apns-expiration",(Instant.now().epochSecond+86400).toString())
                        .header("apns-collapse-id",id.toString()).POST(HttpRequest.BodyPublishers.ofString(json.write(mapOf(
                            "aps" to mapOf("alert" to mapOf("title" to title,"body" to body),"sound" to "default"),
                            "route" to "stock-notifications","notificationId" to id)))).build()
                    val response=http.send(request,HttpResponse.BodyHandlers.ofString())
                    invalid=response.statusCode()==410 || response.statusCode()==400 && runCatching { json.read(response.body()).text("reason")=="BadDeviceToken" }.getOrDefault(false)
                    response.statusCode()==200
                }
                if(invalid)db.update("DELETE FROM push_devices WHERE id=?::uuid AND token=?",device,row["token"])
                else if(success)db.update("UPDATE price_push_deliveries SET sent_at=now() WHERE notification_id=?::uuid AND device_id=?::uuid",id,device)
            } catch(_: Exception) { /* Retain the bounded retry record; never log device tokens or credentials. */ }
        }
    }
}
