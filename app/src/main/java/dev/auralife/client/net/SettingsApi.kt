package dev.auralife.client.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// 全部同步阻塞，调用方需自行开线程
object SettingsApi {
    fun changePassword(api: AuraApi, old: String, new: String): JSONObject =
        api.call("api/account/password", JSONObject().put("oldPassword", old).put("newPassword", new))

    fun stateMe(api: AuraApi): JSONObject = api.call("api/state")

    fun config(api: AuraApi): JSONObject = api.call("api/config")

    fun patchConfig(api: AuraApi, body: JSONObject): JSONObject = api.call("api/config", body, "PATCH")

    fun status(api: AuraApi): JSONObject = api.call("api/status")

    fun logs(api: AuraApi): JSONObject = api.call("api/logs")

    // 返回 {ok, models:[{provider, model}]}
    fun models(api: AuraApi): JSONObject = api.call("api/models")

    fun clearLogs(api: AuraApi): JSONObject = api.call("api/logs/clear", JSONObject(), "POST")

    // what ∈ drive / diary / social
    fun test(api: AuraApi, what: String): JSONObject = api.call("api/test", JSONObject().put("what", what))

    fun life(api: AuraApi): JSONObject = api.call("api/life")

    fun organs(api: AuraApi): JSONObject = api.call("api/organs")

    fun memory(api: AuraApi): JSONObject = api.call("api/memory")

    fun diary(api: AuraApi): JSONObject = api.call("api/diary")

    fun diaryComment(api: AuraApi, entryId: String, text: String): JSONObject =
        api.call("api/diary/comment", JSONObject().put("entryId", entryId).put("text", text))

    fun diaryReply(api: AuraApi, entryId: String, commentId: String, text: String): JSONObject =
        api.call(
            "api/diary/reply",
            JSONObject().put("entryId", entryId).put("commentId", commentId).put("text", text),
        )

    // 服务端已移除删除端点，故不提供删除方法
    fun diaryRun(api: AuraApi, visibility: String = "all"): JSONObject =
        api.call("api/diary/run", JSONObject().put("visibility", visibility))

    fun diarySocialRun(api: AuraApi): JSONObject = api.call("api/diary/social-run", JSONObject(), "POST")

    fun personas(api: AuraApi): JSONObject = api.call("api/personas")

    fun personaDetail(api: AuraApi, id: String): JSONObject =
        api.call("api/personas/detail?id=" + URLEncoder.encode(id, "UTF-8"))

    fun personaSave(api: AuraApi, id: String, name: String, tags: String, body: String): JSONObject =
        api.call(
            "api/personas/save",
            JSONObject().put("id", id).put("name", name).put("tags", tags).put("body", body),
        )

    fun personaDelete(api: AuraApi, id: String): JSONObject =
        api.call("api/personas/delete", JSONObject().put("id", id))

    fun guests(api: AuraApi): JSONObject = api.call("api/guests")

    fun addGuest(api: AuraApi, username: String, displayName: String, note: String, level: String): JSONObject =
        api.call(
            "api/guests",
            JSONObject().put("username", username).put("displayName", displayName).put("note", note).put("level", level),
        )

    fun patchGuest(api: AuraApi, subject: String, body: JSONObject): JSONObject =
        api.call("api/guests/" + URLEncoder.encode(subject, "UTF-8"), body, "PATCH")

    fun deleteGuest(api: AuraApi, subject: String): JSONObject =
        api.call("api/guests/" + URLEncoder.encode(subject, "UTF-8"), null, "DELETE")

    fun stickers(api: AuraApi): JSONObject = api.call("api/stickers")

    fun uploadSticker(api: AuraApi, base64: String, name: String): JSONObject =
        api.call("api/stickers/upload", JSONObject().put("data", base64).put("name", name))

    fun deleteSticker(api: AuraApi, md5: String): JSONObject =
        api.call("api/stickers/" + md5, null, "DELETE")

    fun renameSticker(api: AuraApi, md5: String, name: String, tags: List<String>): JSONObject =
        api.call(
            "api/stickers/" + md5,
            JSONObject().put("name", name).put("tags", JSONArray(tags)),
            "PATCH",
        )

    fun avatarBytes(api: AuraApi): ByteArray? = api.fetchBytes("api/avatar")

    // 返回 {ok, engine, mime, data(base64)}
    fun tts(api: AuraApi, text: String): JSONObject =
        api.call("api/voice/test", JSONObject().put("text", text))

    fun edgeInstalled(api: AuraApi): JSONObject = api.call("api/voice/edge")

    // POST 安装 edge-tts，无 body
    fun edgeInstall(api: AuraApi): JSONObject = api.call("api/voice/edge", JSONObject())
}
