package me.proton.sync.api

import me.proton.sync.api.models.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Proton's REST API.
 *
 * `@Tag accountName` names the app account a call is for, so [AuthInterceptor] can attach that
 * account's token and [TokenRefreshAuthenticator] can refresh it on a 401. It is an OkHttp request
 * TAG, which lives only on the phone: it is never sent. Read it with [protonAccountName].
 *
 * Retrofit keys a @Tag by the parameter's declared type, here String, and refuses a second String
 * @Tag on one method. So no other String @Tag may be added to this interface; anything else that
 * needs a tag gets a type of its own.
 *
 * Up to 1.11.4 this was an `X-Proton-Account-Name` request header. That sent the display name the
 * user chose for the account to Proton on every request, and OkHttp refuses non-ASCII header values,
 * so an account named, say, "Büro" failed every call with IllegalArgumentException and never synced.
 */
interface ProtonApiClient {

    // Auth (v4 endpoints)
    @POST("auth/v4/info")
    suspend fun getAuthInfo(@Body body: Map<String, String>): Response<AuthInfoResponse>

    @POST("auth/v4")
    suspend fun authenticate(@Body body: SrpAuthRequest): Response<AuthResponse>

    /**
     * Revoke THIS session. Matched to Proton's own Go client (go-proton-api `AuthDelete`).
     *
     * Deliberately the singular route. `DELETE auth/v4/sessions` revokes the user's OTHER sessions,
     * which would sign them out of Proton web and every other device: emphatically not what removing
     * one account from this app should do.
     */
    @DELETE("auth/v4")
    suspend fun revokeSession(
        @Tag accountName: String,
        // Passed explicitly rather than left to the interceptor to look up. This call is made WHILE the
        // account is being removed, so by the time it runs the session it needs may already be gone from
        // the session manager. Carrying the credentials on the request makes the ordering irrelevant.
        @Header("Authorization") authorization: String,
        @Header("x-pm-uid") uid: String
    ): Response<Map<String, Any>>

    @POST("auth/v4/2fa")
    suspend fun submitTwoFactor(
        @Tag accountName: String? = null,
        @Body body: TwoFactorRequest
    ): Response<Map<String, Any>>

    @POST("auth/v4/refresh")
    suspend fun refreshToken(@Body body: RefreshTokenRequest): Response<RefreshTokenResponse>

    // User / Keys
    @GET("users")
    suspend fun getUser(@Tag accountName: String? = null): Response<UserResponse>

    @GET("core/v4/keys/salts")
    suspend fun getKeySalts(@Tag accountName: String? = null): Response<KeySaltsResponse>

    @GET("addresses")
    suspend fun getAddresses(@Tag accountName: String? = null): Response<AddressesResponse>

    // Contacts
    @GET("contacts/v4")
    suspend fun getContacts(
        @Tag accountName: String,
        @Query("Page") page: Int = 0,
        @Query("PageSize") pageSize: Int = 100
    ): Response<ContactListResponse>

    @GET("contacts/v4/{contactId}")
    suspend fun getContact(
        @Tag accountName: String,
        @Path("contactId") contactId: String
    ): Response<ContactDetailResponse>

    @POST("contacts/v4")
    suspend fun createContacts(
        @Tag accountName: String,
        @Body body: CreateContactRequest
    ): Response<Map<String, Any>>

    @PUT("contacts/v4/{contactId}")
    suspend fun updateContact(
        @Tag accountName: String,
        @Path("contactId") contactId: String,
        @Body body: UpdateContactRequest
    ): Response<Map<String, Any>>

    @GET("core/v4/labels")
    suspend fun getContactLabels(
        @Tag accountName: String,
        @Query("Type") type: Int = 2
    ): Response<ContactLabelsResponse>

    @PUT("contacts/v4/delete")
    suspend fun deleteContacts(
        @Tag accountName: String,
        @Body body: DeleteContactsRequest
    ): Response<Map<String, Any>>

    @GET("contacts/v4/export")
    suspend fun exportContacts(
        @Tag accountName: String,
        @Query("Page") page: Int = 0,
        @Query("PageSize") pageSize: Int = 50
    ): Response<ContactExportResponse>

    @POST("core/v4/labels")
    suspend fun createContactLabel(
        @Tag accountName: String,
        @Body body: CreateLabelRequest
    ): Response<Map<String, Any>>

    @PUT("core/v4/labels/{labelId}")
    suspend fun updateContactLabel(
        @Tag accountName: String,
        @Path("labelId") labelId: String,
        @Body body: CreateLabelRequest
    ): Response<Map<String, Any>>

    @DELETE("core/v4/labels/{labelId}")
    suspend fun deleteContactLabel(
        @Tag accountName: String,
        @Path("labelId") labelId: String
    ): Response<Map<String, Any>>

    @PUT("contacts/v4/contacts/emails/label")
    suspend fun labelContactEmails(
        @Tag accountName: String,
        @Body body: LabelContactEmailsRequest
    ): Response<Map<String, Any>>

    @PUT("contacts/v4/contacts/emails/unlabel")
    suspend fun unlabelContactEmails(
        @Tag accountName: String,
        @Body body: LabelContactEmailsRequest
    ): Response<Map<String, Any>>

    @GET("contacts/v4/contacts/emails")
    suspend fun queryContactEmails(
        @Tag accountName: String,
        @Query("Email") email: String? = null,
        @Query("Page") page: Int = 0,
        @Query("PageSize") pageSize: Int = 100
    ): Response<Map<String, Any>>

    // Event Loop — unified change detection for contacts + calendar
    @GET("core/v5/events/latest")
    suspend fun getLatestEventId(
        @Tag accountName: String
    ): Response<Map<String, Any>>

    @GET("core/v5/events/{eventId}")
    suspend fun getEvents(
        @Tag accountName: String,
        @Path("eventId") eventId: String
    ): Response<Map<String, Any>>

    // Calendar
    @GET("calendar/v1")
    suspend fun getCalendars(
        @Tag accountName: String
    ): Response<CalendarListResponse>

    /**
     * Proton's CalendarEventsQuery partitions events into 4 buckets (PartDayInsideWindow=0,
     * PartDayBeforeWindow=1, FullDayInsideWindow=2, FullDayBeforeWindow=3). Callers MUST
     * iterate Type=0..3 and merge to retrieve every event in the window — see
     * proton-calendar's getPaginatedEvents.ts. Timezone is also required.
     */
    @GET("calendar/v1/{calendarId}/events")
    suspend fun getCalendarEvents(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String,
        @Query("Page") page: Int = 0,
        @Query("PageSize") pageSize: Int = 100,
        @Query("Start") start: Long? = null,
        @Query("End") end: Long? = null,
        @Query("Timezone") timezone: String? = null,
        @Query("Type") type: Int? = null,
        @Query("MetaDataOnly") metaDataOnly: Int? = null
    ): Response<CalendarEventListResponse>

    @GET("calendar/v1/{calendarId}/modelevents/latest")
    suspend fun getCalendarModelEventLatest(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String
    ): Response<Map<String, Any>>

    @GET("calendar/v1/{calendarId}/events/{eventId}")
    suspend fun getCalendarEvent(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String,
        @Path("eventId") eventId: String
    ): Response<CalendarEventDetailResponse>

    /** The guests past an event's first page (its AttendeesInfo.MoreAttendees is 1), from [page] 1 on. */
    @GET("calendar/v1/{calendarId}/events/{eventId}/attendees")
    suspend fun getCalendarEventAttendees(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String,
        @Path("eventId") eventId: String,
        @Query("Page") page: Int
    ): Response<CalendarEventAttendeesResponse>

    /**
     * Returns the total count of events in a calendar — no date window required.
     * Used by the hybrid fetch dispatcher to decide between chunked /events
     * (good for dense calendars) and /events/ids + per-event GETs (good for
     * sparse calendars). Response: `{"Code": 1000, "Total": N}`.
     */
    @GET("calendar/v1/{calendarId}/events/count")
    suspend fun getCalendarEventsCount(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String
    ): Response<Map<String, Any>>

    /**
     * Returns event IDs for a calendar, paginated by `AfterID` cursor — no date
     * window. Server cap on `Limit` is 100. Response: `{"Code":1000,"IDs":[...]}`.
     */
    @GET("calendar/v1/{calendarId}/events/ids")
    suspend fun getCalendarEventIds(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String,
        @Query("Limit") limit: Int = 100,
        @Query("AfterID") afterId: String? = null
    ): Response<Map<String, Any>>

    @GET("calendar/v1/{calendarId}/bootstrap")
    suspend fun getCalendarBootstrap(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String
    ): Response<Map<String, Any>>

    @GET("calendar/v1/{calendarId}/keys")
    suspend fun getCalendarKeys(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String
    ): Response<CalendarKeyResponse>

    @GET("calendar/v1/{calendarId}/passphrase")
    suspend fun getCalendarPassphrase(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String
    ): Response<CalendarPassphraseResponse>

    @PUT("calendar/v1/{calendarId}/events/{eventId}/attendees/{attendeeId}")
    suspend fun updateAttendeeStatus(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String,
        @Path("eventId") eventId: String,
        @Path("attendeeId") attendeeId: String,
        @Body body: okhttp3.RequestBody
    ): Response<Map<String, Any>>

    /**
     * This calendar's default reminders, used by every event that has no `Notifications` of its
     * own, which is most of them. Without these the phone shows no reminders on events where
     * Proton's own UI shows the calendar defaults.
     */
    @GET("calendar/v1/{calendarId}/settings")
    suspend fun getCalendarSettings(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String
    ): Response<CalendarSettingsResponse>

    /**
     * Update only THIS member's personal part of an event: their reminders and their per-event
     * colour. Both are per-member data and neither is inside the encrypted iCal.
     *
     * This is deliberately separate from the events/sync payload below. A sync update carries the
     * attendee list, and a member who is not the organiser is not allowed to touch that, so Proton
     * rejects the whole thing with code 2011 "Not allowed to update attendee list". Routing
     * personal data here means a guest on a shared calendar can still set their own reminder or
     * colour on an event they cannot otherwise modify. Mirrors Proton's own client, which calls
     * PUT .../events/{id}/personal with exactly {Notifications, Color}
     * (android-calendar 2.33.0, CalendarsApiImpl.updateEventPersonalPart).
     */
    @PUT("calendar/v1/{calendarId}/events/{eventId}/personal")
    suspend fun updateEventPersonalPart(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String,
        @Path("eventId") eventId: String,
        @Body body: UpdateEventPersonalPartRequest
    ): Response<Map<String, Any>>

    @PUT("calendar/v1/{calendarId}/events/sync")
    suspend fun syncCalendarEvents(
        @Tag accountName: String,
        @Path("calendarId") calendarId: String,
        @Body body: CalendarEventSyncRequest
    ): Response<Map<String, Any>>

    // Mail — calendar invitation sending via draft+send flow
    @POST("mail/v4/messages")
    suspend fun createDraft(
        @Tag accountName: String,
        @Body body: okhttp3.RequestBody
    ): Response<Map<String, Any>>

    @POST("mail/v4/messages/{draftId}")
    suspend fun sendDraft(
        @Tag accountName: String,
        @Path("draftId") draftId: String,
        @Body body: okhttp3.RequestBody
    ): Response<Map<String, Any>>

    @Multipart
    @POST("mail/v4/attachments")
    suspend fun uploadAttachment(
        @Tag accountName: String,
        @Part("MessageID") messageId: okhttp3.RequestBody,
        @Part("Filename") filename: okhttp3.RequestBody,
        @Part("MIMEType") mimeType: okhttp3.RequestBody,
        @Part("ContentID") contentId: okhttp3.RequestBody,
        @Part keyPackets: okhttp3.MultipartBody.Part,
        @Part dataPacket: okhttp3.MultipartBody.Part,
        @Part signature: okhttp3.MultipartBody.Part
    ): Response<Map<String, Any>>

    @HTTP(method = "PUT", path = "mail/v4/messages/delete", hasBody = true)
    suspend fun deleteMessages(
        @Tag accountName: String,
        @Body body: okhttp3.RequestBody
    ): Response<Map<String, Any>>

    // Public keys for external recipients (calendar invitations)
    @GET("keys")
    suspend fun getPublicKeys(
        @Tag accountName: String,
        @Query("Email") email: String
    ): Response<Map<String, Any>>
}
