package dev.sluide.fileviewers.bicep

/**
 * A realistic Bicep file: decorators, user-defined types, a function, interpolated and multi-line strings, a storage
 * account with a nested child, a loop with `parent:`, an existing key vault, a conditional module with `dependsOn`.
 */
internal val MAIN_BICEP =
    """
    |// Storage for the app
    |targetScope = 'resourceGroup'
    |
    |metadata description = 'Storage, network and secrets'
    |
    |import { tagSet as tags } from 'types.bicep'
    |
    |@description('Where everything goes')
    |param location string = resourceGroup().location
    |
    |@secure()
    |@minLength(12)
    |param adminPassword string
    |
    |@sys.description('Containers to create')
    |param containerNames array = [
    |  'logs'
    |  'data'
    |]
    |
    |param config object = {}
    |
    |type skuName = 'Standard_LRS' | 'Premium_LRS'
    |
    |type tagSet = {
    |  owner: string
    |  'cost-center': int?
    |}
    |
    |func sayHello(who string) string => 'Hello ${'$'}{who}'
    |
    |var prefix = 'app${'$'}{uniqueString(resourceGroup().id)}'
    |var notes = '''
    |No ${'$'}{interpolation} here, it's 'raw'
    |'''
    |var escaped = 'it\'s \${'$'}{not} interpolated'
    |var enabled = !empty(location) && length(prefix) > 3 || false
    |var fallback = config.?name ?? null
    |var suffixes = map(containerNames, each => '${'$'}{each}-x')
    |var matches = prefix =~ 'APP' ? 1 : 0
    |var storageId = storage.id
    |var ids = [storageId]
    |var count = string(length(ids))
    |var group = az.resourceGroup()
    |// resource fake 'x' = {}
    |
    |/* The storage account
    |   and its container */
    |resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
    |  name: prefix
    |  location: location
    |  sku: {
    |    name: 'Standard_LRS'
    |  }
    |  kind: 'StorageV2'
    |  properties: {
    |    minimumTlsVersion: 'TLS1_2'
    |    type: storage.type
    |  }
    |
    |  resource blobs 'blobServices' = {
    |    name: 'default'
    |  }
    |}
    |
    |@batchSize(2)
    |resource container 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = [for name in containerNames: {
    |  parent: storage::blobs
    |  name: name
    |}]
    |
    |resource vault 'Microsoft.KeyVault/vaults@2023-07-01' existing = {
    |  name: 'kv-${'$'}{prefix}'
    |}
    |
    |module network './modules/network.bicep' = if (enabled) {
    |  name: 'network'
    |  params: {
    |    location: location
    |    storageIds: ids
    |  }
    |  dependsOn: [
    |    vault
    |  ]
    |}
    |
    |output endpoint string = storage.properties.primaryEndpoints.blob
    |
    """.trimMargin()

/** A parameter file for [MAIN_BICEP]. */
internal val PARAMS_BICEP =
    """
    |using './main.bicep'
    |
    |param location = 'westeurope'
    |param containerNames = [
    |  'logs'
    |]
    |// Secrets come from the environment
    |param adminPassword = readEnvironmentVariable('ADMIN_PASSWORD', '')
    |
    """.trimMargin()
