package com.shyfr.app.msg

import com.shyfr.app.api.*

class ApiRemoteKeys(private val client: ShyfrApiClient) : RemoteKeys {
    override fun bundles(username: String): List<DeviceBundle> = client.fetchKeyBundles(username)
}
