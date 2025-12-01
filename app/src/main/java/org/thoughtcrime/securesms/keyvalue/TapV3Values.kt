package org.thoughtcrime.securesms.keyvalue

class TapV3Values internal constructor(store: KeyValueStore) : SignalStoreValues(store) {
    
    public override fun onFirstEverAppLaunch() {
    }
    
    public override fun getKeysToIncludeInBackup(): List<String> {
        return emptyList()
    }
    
    fun putStringValue(key: String, value: String) {
        putString(key, value)
    }
    
    fun getStringValue(key: String, defaultValue: String?): String? {
        return getString(key, defaultValue)
    }
    
    fun putIntegerValue(key: String, value: Int) {
        putInteger(key, value)
    }
    
    fun getIntegerValue(key: String, defaultValue: Int): Int {
        return getInteger(key, defaultValue)
    }
    
    fun removeValue(key: String) {
        remove(key)
    }
}
