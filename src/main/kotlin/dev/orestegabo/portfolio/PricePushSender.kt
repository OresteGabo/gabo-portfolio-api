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
        val rows=db.queryForList("""SELECT q.notification_id::text,q.device_id::text,d.platform,d.token,d.environment,d.language,d.live_start_token,n.ticker,n.kind,n.payload,q.sent_at,q.live_sent_at,n.owner_id
            FROM price_push_deliveries q JOIN notifications n ON n.id=q.notification_id
            JOIN push_devices d ON d.id=q.device_id AND d.owner_id=n.owner_id
            WHERE (q.sent_at IS NULL OR n.kind='subscriptions' AND q.live_sent_at IS NULL AND d.live_start_token IS NOT NULL) AND q.attempts<5 AND q.next_attempt_at<=now() AND n.read_at IS NULL
                AND (n.kind<>'subscriptions' OR EXISTS(SELECT 1 FROM bond_month_reminders r WHERE r.owner_id=n.owner_id AND r.month=n.payload->>'month'))
                AND NOT EXISTS(SELECT 1 FROM bond_users u WHERE u.id=n.owner_id AND u.disabled_at IS NOT NULL) AND COALESCE((SELECT (categories->>n.kind)::boolean FROM notification_preferences WHERE owner_id=n.owner_id),true) AND n.created_at>=now()-interval '3 days' ORDER BY n.created_at LIMIT 20""")
        endExpiredActivities()
        for(row in rows) {
            if((System.nanoTime()-started)>45_000_000_000L)break
            val payload=json.read(row["payload"].toString())
            val subscription=row.text("kind")=="subscriptions"
            if(subscription && !bondReminderDeliverable(payload,notificationToday())) {
                db.update("UPDATE price_push_deliveries SET attempts=5 WHERE notification_id=?::uuid AND device_id=?::uuid",row["notification_id"],row["device_id"])
                continue
            }
            val platform=row.text("platform")
            if(platform=="android" && firebaseProject.isBlank() || platform=="ios" && (appleKey.isBlank() || appleTeam.isBlank() || appleKeyId.isBlank()))continue
            val id=row["notification_id"];val device=row["device_id"]
            if(db.update("UPDATE price_push_deliveries SET attempts=attempts+1,next_attempt_at=now()+interval '5 minutes' WHERE notification_id=?::uuid AND device_id=?::uuid AND (sent_at IS NULL OR live_sent_at IS NULL) AND next_attempt_at<=now()",id,device)!=1)continue
            try {
                // No balance, price, holding quantity or account identity on the lock screen.
                val french=row.text("language")=="fr"
                val title=if(subscription) "Struct Capital · " + payload.text("title") else "Struct Capital"
                val body=if(subscription) bondReminderText(payload.text("stage"),french) else if(french)"Une nouvelle notification est disponible dans l’application." else "You have a new notification. Open the app for details."
                var invalid=false
                val success=if(row["sent_at"]!=null) true else if(platform=="android") {
                    try {
                        val message=Message.builder().setToken(row.text("token"))
                            .putData("route","stock-notifications").putData("notificationId",id.toString())
                        if(subscription) {
                            // Data-only high priority wakes the app to build/update the ongoing card.
                            message.putAllData(mapOf("kind" to "subscriptions", "title" to payload.text("title"),
                                "body" to body, "stage" to payload.text("stage"), "month" to payload.text("month"),
                                "calendarId" to payload.text("calendarId"), "endsAt" to payload.text("endsAt"),
                                "dueDate" to payload.text("dueDate")))
                                .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).setTtl(3600000).build())
                        } else message.setNotification(Notification.builder().setTitle(title).setBody(body).build())
                            .setAndroidConfig(AndroidConfig.builder().setTtl(86400000).setNotification(AndroidNotification.builder()
                                .setChannelId("price_changes").setTag(id.toString()).setVisibility(AndroidNotification.Visibility.PRIVATE).build()).build())
                        firebase.send(message.build())
                        true
                    } catch(e: FirebaseMessagingException) { invalid=e.messagingErrorCode==MessagingErrorCode.UNREGISTERED;false }
                } else {
                    val host=if(row.text("environment")=="sandbox")"api.sandbox.push.apple.com" else "api.push.apple.com"
                    val request=HttpRequest.newBuilder(URI("https://$host/3/device/"+row.text("token"))).timeout(Duration.ofSeconds(8))
                        .header("authorization","bearer "+appleToken()).header("apns-topic",appleTopic).header("apns-push-type","alert")
                        .header("apns-priority","10").header("apns-expiration",(Instant.now().epochSecond+86400).toString())
                        .header("apns-collapse-id",id.toString()).POST(HttpRequest.BodyPublishers.ofString(json.write(mapOf(
                            "aps" to mapOf("alert" to mapOf("title" to title,"body" to body),"sound" to "default"),
                            "route" to "stock-notifications","notificationId" to id)))).build()
                    val response=http.send(request,HttpResponse.BodyHandlers.ofString())
                    invalid=response.statusCode()==410 || response.statusCode()==400 && runCatching { json.read(response.body()).text("reason")=="BadDeviceToken" }.getOrDefault(false)
                    response.statusCode()==200
                }
                if(invalid)db.update("DELETE FROM push_devices WHERE id=?::uuid AND token=?",device,row["token"])
                else if(success) {
                    db.update("UPDATE price_push_deliveries SET sent_at=COALESCE(sent_at,now()) WHERE notification_id=?::uuid AND device_id=?::uuid",id,device)
                    if(subscription && platform=="ios" && row["live_sent_at"]==null && row.text("live_start_token").isNotBlank() && payload.text("stage") in listOf("open","closing")) {
                        val now=Instant.now().epochSecond
                        val activities=db.queryForList("SELECT token FROM bond_live_activities WHERE device_id=?::uuid AND calendar_id=? AND expires_at>now()",device,payload.text("calendarId"))
                        val sent=if(activities.isEmpty()) appleLive(row.text("environment"),row.text("live_start_token"),bondLiveStartPayload(payload,french,now),id.toString())
                            else activities.map { activity -> appleLive(row.text("environment"),activity.text("token"),mapOf("aps" to mapOf(
                                "timestamp" to now,"event" to "update", "content-state" to mapOf("stage" to payload.text("stage"),"endsAt" to payload.text("endsAt").toDouble()),
                                "stale-date" to minOf(payload.text("endsAt").toLong(),now+28800))),id.toString()) }.all { it }
                        if(sent)
                            db.update("UPDATE price_push_deliveries SET live_sent_at=now() WHERE notification_id=?::uuid AND device_id=?::uuid",id,device)
                    } else db.update("UPDATE price_push_deliveries SET live_sent_at=COALESCE(live_sent_at,now()) WHERE notification_id=?::uuid AND device_id=?::uuid",id,device)
                }
            } catch(_: Exception) { /* Retain the bounded retry record; never log device tokens or credentials. */ }
        }
    }

    private fun appleLive(environment: String, token: String, payload: Record, collapse: String): Boolean {
        if(appleKey.isBlank() || appleTeam.isBlank() || appleKeyId.isBlank())return false
        val host=if(environment=="sandbox")"api.sandbox.push.apple.com" else "api.push.apple.com"
        val request=HttpRequest.newBuilder(URI("https://$host/3/device/$token")).timeout(Duration.ofSeconds(8))
            .header("authorization","bearer "+appleToken()).header("apns-topic",appleTopic+".push-type.liveactivity")
            .header("apns-push-type","liveactivity").header("apns-priority","10")
            .header("apns-expiration",(Instant.now().epochSecond+3600).toString()).header("apns-collapse-id",collapse.take(64))
            .POST(HttpRequest.BodyPublishers.ofString(json.write(payload))).build()
        return http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200
    }
    private fun endExpiredActivities() {
        // iOS has removed these after its 8h active + 4h retention maximum.
        db.update("DELETE FROM bond_live_activities WHERE expires_at<now()-interval '4 hours'")
        val rows=db.queryForList("""SELECT a.*,d.environment FROM bond_live_activities a JOIN push_devices d ON d.id=a.device_id
            WHERE a.expires_at<=now() OR NOT EXISTS(SELECT 1 FROM bond_month_reminders r WHERE r.owner_id=d.owner_id
                AND r.month=(SELECT payload->>'month' FROM notifications WHERE payload->>'calendarId'=a.calendar_id LIMIT 1))
            OR EXISTS(SELECT 1 FROM notifications n WHERE n.owner_id=d.owner_id AND n.kind='subscriptions' AND n.payload->>'calendarId'=a.calendar_id AND n.payload->>'stage'='closed')
            LIMIT 100""")
        for(row in rows) runCatching {
            val now=Instant.now().epochSecond
            val expired=(row["expires_at"] as java.sql.Timestamp).toInstant().epochSecond<=now
            val state=mapOf("stage" to if(expired)"ended" else "closed", "endsAt" to now.toDouble())
            val payload=mapOf("aps" to mapOf("timestamp" to now,"event" to "end","content-state" to state,
                "dismissal-date" to if(expired)now+14400 else now))
            if(appleLive(row.text("environment"),row.text("token"),payload,row.text("activity_id")))
                db.update("DELETE FROM bond_live_activities WHERE device_id=? AND activity_id=?",row["device_id"],row["activity_id"])
        }
    }
}

internal fun bondReminderText(stage: String, french: Boolean): String = when(stage) {
    "form" -> if(french)"Le formulaire de souscription est disponible." else "Your bond application form is available."
    "open" -> if(french)"Les souscriptions sont ouvertes. Consultez le formulaire et les dates." else "Subscriptions are open. View the form and published dates."
    "closing" -> if(french)"Les souscriptions se terminent bientôt. Vérifiez l’heure limite avec votre courtier." else "Subscriptions close soon. Confirm the deadline with your broker."
    "closed" -> if(french)"La période de souscription est terminée." else "The published subscription window has ended."
    else -> "Struct Capital"
}
internal fun bondLiveStartPayload(payload: Record, french: Boolean, now: Long): Record {
    val endsAt=payload.text("endsAt").toLongOrNull() ?: now
    return mapOf("aps" to mapOf("timestamp" to now,"event" to "start","attributes-type" to "BondSubscriptionAttributes",
        "attributes" to mapOf("calendarId" to payload.text("calendarId"),"title" to payload.text("title"),"month" to payload.text("month"),"language" to if(french)"fr" else "en"),
        "content-state" to mapOf("stage" to payload.text("stage"),"endsAt" to endsAt.toDouble()),
        "stale-date" to minOf(endsAt,now+28800),
        "alert" to mapOf("title" to "Struct Capital","body" to bondReminderText(payload.text("stage"),french))))
}
