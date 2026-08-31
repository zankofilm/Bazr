package ir.javanrood.bazr

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class DeviceSecurity(private val context: Context) {
    private val prefs=context.getSharedPreferences("bazr_secure_meta",Context.MODE_PRIVATE)
    private val signAlias="bazr_device_sign_v1"
    private val aesAlias="bazr_device_aes_v1"
    val deviceId:String get()=prefs.getString("device_id",null) ?: UUID.randomUUID().toString().also{prefs.edit().putString("device_id",it).apply()}

    init { ensureSigningKey(); ensureAesKey() }

    private fun ks()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    private fun ensureSigningKey(){
        if(ks().containsAlias(signAlias))return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply{
            initialize(KeyGenParameterSpec.Builder(signAlias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())
        }.generateKeyPair()
    }
    private fun ensureAesKey(){
        if(ks().containsAlias(aesAlias))return
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(aesAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun publicKeyPem():String{
        val der=ks().getCertificate(signAlias).publicKey.encoded
        val b=Base64.encodeToString(der,Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n"+b.chunked(64).joinToString("\n")+"\n-----END PUBLIC KEY-----"
    }
    fun sign(message:String):String{
        val key=ks().getKey(signAlias,null)
        val sig=Signature.getInstance("SHA256withECDSA");sig.initSign(key as java.security.PrivateKey);sig.update(message.toByteArray())
        return Base64.encodeToString(sig.sign(),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    fun saveDeviceToken(token:String){
        val key=ks().getKey(aesAlias,null) as javax.crypto.SecretKey
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key)
        val enc=c.doFinal(token.toByteArray())
        prefs.edit().putString("token_iv",Base64.encodeToString(c.iv,Base64.NO_WRAP)).putString("token_ct",Base64.encodeToString(enc,Base64.NO_WRAP)).apply()
    }
    fun loadDeviceToken():String?{
        val iv=prefs.getString("token_iv",null)?:return null;val ct=prefs.getString("token_ct",null)?:return null
        return try{
            val key=ks().getKey(aesAlias,null) as javax.crypto.SecretKey
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(ct,Base64.NO_WRAP)))
        }catch(_:Throwable){null}
    }
    fun saveActivationRequest(id:String)=prefs.edit().putString("activation_request",id).apply()
    fun activationRequest():String?=prefs.getString("activation_request",null)
    fun saveProfile(name:String, inspectorRef:String, role:String="inspector")=prefs.edit().putString("name",name).putString("inspector_ref",inspectorRef).putString("role",role).apply()
    fun profileName()=prefs.getString("name","کاربر")?:"کاربر"
    fun profileRole()=prefs.getString("role","inspector")?:"inspector"
    fun inspectorRef()=prefs.getString("inspector_ref","")?:""
}
