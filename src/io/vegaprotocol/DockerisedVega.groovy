/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable CompileStatic */
package io.vegaprotocol

import com.cloudbees.groovy.cps.NonCPS

prefix
portbase
basedir
dockerisedvagaScript
validators
nonValidators
genesisFile
marketProposalsFile
dlv

vegaCoreVersion
dataNodeVersion
goWalletVersion
ethereumEventForwarderVersion

dockerImageVegaCore
dockerImageDataNode
dockerImageGoWallet
dockerImageEthereumEventForwarder

vegatoolsScript

tendermintLogLevel
vegaCoreLogLevel

void init(Map config=[:]) {
    assert config.prefix : 'prefix is required'
    prefix = config.prefix
    assert config.portbase : 'portbase is required'
    portbase = config.portbase
    assert config.basedir : 'basedir is required'
    basedir = config.basedir
    dockerisedvagaScript = config.dockerisedvagaScript ?: 'devops-infra/scripts/dockerisedvega.sh'
    assert config.validators : 'validators is required'
    validators = config.validators
    assert config.nonValidators : 'nonValidators is required'
    nonValidators = config.nonValidators

    genesisFile = config.genesisFile
    marketProposalsFile = config.marketProposalsFile
    dlv = config.dlv ?: false

    vegaCoreVersion = config.vegaCoreVersion
    dataNodeVersion = config.dataNodeVersion
    goWalletVersion = config.goWalletVersion
    ethereumEventForwarderVersion = config.ethereumEventForwarder

    dockerImageVegaCore = "docker.pkg.github.com/vegaprotocol/vega/vega:${vegaCoreVersion}"
    dockerImageDataNode = "docker.pkg.github.com/vegaprotocol/data-node/data-node:${dataNodeVersion}"
    dockerImageGoWallet = "vegaprotocol/go-wallet:${goWalletVersion}"
    dockerImageEthereumEventForwarder = "vegaprotocol/ethereum-event-forwarder:${ethereumEventForwarderVersion}"

    assert config.vegatoolsScript : 'vegatoolsScript is required'
    vegatoolsScript = config.vegatoolsScript

    tendermintLogLevel = config.tendermintLogLevel ?: 'info'
    vegaCoreLogLevel = config.vegaCoreLogLevel ?: 'Info'
}

@NonCPS
@Override
String toString() {
    return "DockerisedVega(prefix: \"${prefix}\", portbase: ${portbase}, " +
        "basedir: \"${basedir}\", dockerisedvagaScript: \"${dockerisedvagaScript}\", " +
        "validators: ${validators}, nonValidators: ${nonValidators}, " +
        "genesisFile: \"${genesisFile}\", marketProposalsFile: \"${marketProposalsFile}\", " +
        "dlv: ${dlv}, vegaCoreVersion: \"${vegaCoreVersion}\", dataNodeVersion: \"${dataNodeVersion}\", " +
        "goWalletVersion: \"${goWalletVersion}\", " +
        "dockerImageVegaCore=\"${dockerImageVegaCore}\", dockerImageDataNode=\"${dockerImageDataNode}\", " +
        "dockerImageGoWallet=\"${dockerImageGoWallet}\", " +
        "dockerImageEthereumEventForwarder=\"${dockerImageEthereumEventForwarder}\", " +
        "vegatoolsScript=\"${vegatoolsScript}\"" +
        "tendermintLogLevel=\"${tendermintLogLevel}\", vegaCoreLogLevel=\"${vegaCoreLogLevel}\")"
}

void run(String command, boolean resume = false) {
    String extraArguments = ''
    if (vegaCoreVersion) {
        extraArguments += " --vega-version \"${vegaCoreVersion}\""
    }
    if (dataNodeVersion) {
        extraArguments += " --datanode-version \"${dataNodeVersion}\""
    }
    if (goWalletVersion) {
        extraArguments += " --vegawallet-version \"${goWalletVersion}\""
    }
    if (ethereumEventForwarderVersion) {
        extraArguments += " --eef-version \"${ethereumEventForwarderVersion}\""
    }
    if (genesisFile) {
        extraArguments += " --genesis \"${genesisFile}\""

    }
    if (marketProposalsFile) {
        extraArguments += " --proposals \"${marketProposalsFile}\""
    }
    if (dlv) {
        extraArguments += ' --dlv'
    }
    if (resume) {
        extraArguments += ' --resume'
    }
    sh label: 'start dockerised-vega', script: """#!/bin/bash -e
        ${dockerisedvagaScript} \
            --datadir "${basedir}" \
            --prefix "${prefix}" \
            --portbase "${portbase}" \
            --validators "${validators}" \
            --nonvalidators "${nonValidators}" \
            --tendermint-loglevel "${tendermintLogLevel}" \
            --vega-loglevel "${vegaCoreLogLevel}" \
            ${extraArguments} \
            ${command}
    """
}

void start(Map config) {
    boolean resume = config?.resume ?: false
    run('start', resume)
}

void restart(Map config) {
    boolean resume = config?.resume ?: false
    run('restart', resume)
}

void stop(Map config) {
    String extraArguments = ''
    if (config?.resume) {
        extraArguments += ' --resume'
    }
    sh label: 'stop dockerised-vega', script: """#!/bin/bash -e
        ${dockerisedvagaScript} \
            --prefix '${prefix}' \
            --portbase '${portbase}' \
            ${extraArguments} \
            stop
    """
}

void pull() {
    sh label: 'docker pull for dockerised-vega', script: """#!/bin/bash -e
        ${dockerisedvagaScript} pull
    """
}

void printAllContainers() {
    sh label: 'list all the containers', script: """#!/bin/bash -e
        docker ps -a --filter "name=${prefix}"
    """
}

void printAllCheckpoints(int node=0) {
    String nodeDataDir = "${basedir}/dockerised-${prefix}/vega/node${node}"
    String checkpointDir = "${nodeDataDir}/.local/state/vega/node/checkpoints"
    sh label: 'list all the checkpoints', script: """#!/bin/bash -e
        find \"${checkpointDir}\" -name '20*.cp'|sort
    """
}

List<String> getDockerContainerNames() {
    return sh(
            script: "docker ps -a --filter 'name=${prefix}' --format '{{.Names}}'",
            returnStdout: true,
        ).trim().split('\n')
}

List<String> getRunningContainerNames() {
    return sh(
            script: "docker ps -a --filter 'name=${prefix}' --filter 'status=running' --format '{{.Names}}'",
            returnStdout: true,
        ).trim().split('\n')
}

boolean isNodeRunning(int node=0) {
    for (String containerName in getRunningContainerNames()) {
        if (containerName.endsWith("vega-node${node}")) {
            return true
        }
    }
    return false
}

String getLatestCheckpointFilepath(int node=0) {
    String nodeDataDir = "${basedir}/dockerised-${prefix}/vega/node${node}"
    String checkpointDir = "${nodeDataDir}/.local/state/vega/node/checkpoints"
    return sh(
            script: "find \"${checkpointDir}\" -name '20*.cp'|sort|tail -1",
            returnStdout: true,
        ).trim()
}

String waitForNextCheckpoint(int node=0) {
    printAllContainers()
    printAllCheckpoints()
    // TODO: improve, cos now there is too many Jenkins Steps created
    String secondLastCheckpointFile = getLatestCheckpointFilepath(node)
    echo "Current last checkpoint: ${secondLastCheckpointFile}"
    while (true) {
        sleep(time:5, unit:'SECONDS')
        String currentLastCheckpointFile = getLatestCheckpointFilepath(node)
        if (secondLastCheckpointFile != currentLastCheckpointFile) {
            echo "Found a new checkpoint: ${currentLastCheckpointFile}"
            return currentLastCheckpointFile
        }
    }
}

void saveLatestCheckpointToFile(String targetFile, int node=0) {
    String checkpointFile = getLatestCheckpointFilepath(node)
    sh label: 'Convert checkpoint to json format', script: """
        mkdir -p "\$(dirname ${targetFile})"
        ${vegatoolsScript} checkpoint -v \
            -f "${checkpointFile}" \
            -o "${targetFile}"
    """
}

void saveGenesisToFile(String targetFile, int node=0) {
    String genesisFile = "${basedir}/dockerised-${prefix}/tendermint/node${node}/config/genesis.json"
    sh label: "Copy genesis to ${targetFile}", script: """
        mkdir -p "\$(dirname ${targetFile})"
        cp "${genesisFile}" "${targetFile}"
    """
}
