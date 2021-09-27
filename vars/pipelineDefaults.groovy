/* groovylint-disable DuplicateStringLiteral */
/*
 * Jenkins sometimes ignores default paramter values and uses values from the previous build
 * To avoid that flaky behaviour, the default parameter values are set explicitly in multiple places
 */
import groovy.transform.Field

// Dockerised Vega pipeline
@Field
Map dv = [
    vegaCoreBranch: 'develop',
    dataNodeBranch: 'develop',
    goWalletBranch: 'develop',
    devopsInfraBranch: 'master',
    vegatoolsBranch: 'develop',

    validatorNodeCount: '2',
    nonValidatorNodeCount: '1',

    genesisJSON: '',
    proposalsJSON: '',

    tendermintLogLevel: 'info',
    vegaCoreLogLevel: 'Info',
    vegaCoreDLV: false,

    agent: 'system-tests',
    timeout: '100'
]

// System Tests pipeline
@Field
Map st = dv + [
    testDirectory: 'CoreTesting/bvt',
    testFunction: '',
    protosBranch: 'develop',
    systemTestsBranch: 'develop',
    systemTestsDebug: false,
    genesisJSON: 'system-tests/docker/zero-genesis.json'
]

return this
