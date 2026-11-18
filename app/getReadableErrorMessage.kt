private fun getReadableErrorMessage(e: Throwable?): String {
        if (e == null) return "Errore durante il download"
        val msg = e.localizedMessage?.trim() ?: e.message?.trim() ?: ""
        if (msg.isNotEmpty() && msg != "null") return msg
        val causeMsg = e.cause?.localizedMessage?.trim() ?: e.cause?.message?.trim() ?: ""
        if (causeMsg.isNotEmpty() && causeMsg != "null") return causeMsg
        return when (e) {
            is java.io.EOFException -> "Connessione interrotta dal server (download incompleto)"
            is java.net.SocketTimeoutException -> "Tempo di attesa dal server scaduto"
            is java.net.UnknownHostException -> "Impossibile raggiungere il server (rete assente)"
            is javax.crypto.BadPaddingException -> "Chiave di decifratura errata o file corrotto"
            is javax.crypto.IllegalBlockSizeException -> "Dimensione blocco cifrato non valida"
            else -> e.javaClass.simpleName
        }
    }

    fun startFileDownload(
        context: Context,
        message: Message,
        groupKey: String?,
        groupIv: String?,
        currentUserId: Int,
        onFinish: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val fileId = message.fileId ?: return
        if (DownloadTracker.isDownloading(fileId)) return

        val job = downloadScope.launch {
            val prefs = SecurePrefs.get(context)
            val msgGroupId = if (message.groupId != 0) message.groupId else groupId
            val isZk = prefs.getBoolean("is_zk_$msgGroupId", false)

            // 1. Tenta prima con la chiave della modalità corrente (ZK o Standard)
            val primaryKey = if (isZk) prefs.getString("zk_key_$msgGroupId", groupKey) ?: groupKey else groupKey
            val primaryIv = if (isZk) prefs.getString("zk_iv_$msgGroupId", groupIv) ?: groupIv else groupIv

            // 2. Chiave di riserva per file inviati prima/dopo il cambio di modalità
            val fallbackKey = if (isZk) prefs.getString("key_$msgGroupId", null) else prefs.getString("zk_key_$msgGroupId", null)
            val fallbackIv = if (isZk) prefs.getString("iv_$msgGroupId", null) else prefs.getString("zk_iv_$msgGroupId", null)

            if (primaryKey == null || primaryIv == null) {
                withContext(Dispatchers.Main) { onError(Exception("Chiavi di cifratura mancanti.")) }
                DownloadTracker.finishDownload(fileId)
                return@launch
            }

            val tempEncryptedFile = File(context.cacheDir, "temp_${fileId}_${System.currentTimeMillis()}.enc")
            val finalDecryptedFile = FileUtils.getLocalFile(context, message)

            var attempt = 0
            var success = false
            var lastException: Exception? = null
            val bufferSize = 64 * 1024

            while (attempt < 3 && !success && isActive) {
                try {
                    attempt++
                    val downloadUrl = "${ApiClient.BASE_URL}download.php?user_id=$currentUserId&file_id=$fileId"
                    val response = ApiClient.instance.downloadFile(downloadUrl).execute()

                    if (!response.isSuccessful) throw IOException("Errore server HTTP ${response.code()}")
                    val body = response.body() ?: throw IOException("Risposta vuota dal server")

                    val contentType = body.contentType()
                    if (contentType != null && (contentType.type == "text" || contentType.subtype.contains("html") || contentType.subtype.contains("json"))) {
                        throw IOException("Il file non esiste più sul server o l'accesso è negato.")
                    }

                    val totalSize = body.contentLength()
                    var bytesCopied = 0L
                    var lastProgress = -1
                    var lastProgressTime = 0L

                    body.byteStream().use { inputStream ->
                        FileOutputStream(tempEncryptedFile).use { outputStream ->
                            val buffer = ByteArray(bufferSize)
                            var bytes = inputStream.read(buffer)
                            while (bytes >= 0 && isActive) {
                                outputStream.write(buffer, 0, bytes)
                                bytesCopied += bytes
                                if (totalSize > 0) {
                                    val progress = (100 * bytesCopied / totalSize).toInt().coerceIn(0, 100)
                                    val now = System.currentTimeMillis()
                                    if (progress != lastProgress && (now - lastProgressTime > 100 || progress == 100)) {
                                        lastProgress = progress
                                        lastProgressTime = now
                                        DownloadTracker.updateProgress(fileId, progress)
                                    }
                                }
                                bytes = inputStream.read(buffer)
                            }
                        }
                    }

                    if (totalSize > 0 && bytesCopied < totalSize) {
                        throw java.io.EOFException("Connessione interrotta: scaricati $bytesCopied di $totalSize byte")
                    }

                    if (tempEncryptedFile.length() < 16) {
                        throw IOException("File scaricato non valido o vuoto sul server.")
                    }

                    DownloadTracker.updateProgress(fileId, 100)

                    // DECIFRATURA FLESSIBILE: Se fallisce con la chiave primaria, tenta automaticamente la chiave di riserva
                    var decryptedOk = false
                    try {
                        FileInputStream(tempEncryptedFile).use { fis ->
                            FileOutputStream(finalDecryptedFile).use { fos ->
                                EncryptionHelper.decryptStream(fis, fos, primaryKey, primaryIv)
                            }
                        }
                        decryptedOk = true
                    } catch (e: Exception) {
                        Log.w("MessageAdapter", "Decifratura con chiave primaria fallita per file $fileId. Tento con chiave di riserva...", e)
                        if (fallbackKey != null && fallbackIv != null) {
                            try {
                                FileInputStream(tempEncryptedFile).use { fis ->
                                    FileOutputStream(finalDecryptedFile).use { fos ->
                                        EncryptionHelper.decryptStream(fis, fos, fallbackKey, fallbackIv)
                                    }
                                }
                                decryptedOk = true
                            } catch (e2: Exception) {
                                Log.e("MessageAdapter", "Decifratura fallita anche con la chiave di riserva per file $fileId", e2)
                            }
                        }
                    }

                    if (decryptedOk) {
                        success = true
                    } else {
                        throw IOException("Impossibile decifrare il file: chiavi errate o file corrotto.")
                    }

                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    lastException = e
                    if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
                    if (finalDecryptedFile.exists()) finalDecryptedFile.delete()

                    val msgLower = (e.message ?: "").lowercase(Locale.ROOT)
                    if (msgLower.contains("decifrare") || msgLower.contains("non esiste") || msgLower.contains("negato")) {
                        break
                    }

                    if (attempt < 3 && isActive) {
                        DownloadTracker.updateProgress(fileId, 0)
                        delay(500)
                    }
                }
            }

            if (tempEncryptedFile.exists()) tempEncryptedFile.delete()

            withContext(Dispatchers.Main) {
                DownloadTracker.finishDownload(fileId)
                if (success) {
                    onFinish(finalDecryptedFile)
                } else {
                    val errorMsg = getReadableErrorMessage(lastException)
                    onError(Exception(errorMsg, lastException))
                }
            }
        }

        DownloadTracker.startDownload(fileId, job)
    }