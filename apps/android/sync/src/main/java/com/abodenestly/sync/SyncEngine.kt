package com.abodenestly.sync

/**
 * What screens call to ask for fresh data. SHOP-08's push/pull loop replaces `ActivityPull` (:core) behind this
 * seam; screens and their triggers stay as they are. Twins: iOS `SyncEngine`, web `SyncEngine.ts`.
 */
interface SyncEngine {
    fun kick()
}
