String gitHash(String directory, int hashLength=8) {
    if (hashLength < 1 || hashLength > 40) {
        error("[gitHash] hashLength should be between 1 and 40. Given: " + hashLength)
    }

    String hash = ""
    dir(directory) {
        hash = sh (
            script: 'git rev-parse HEAD',
            returnStdout: true,
        ).trim()
    }

    return hash.substring(0, hashLength)
}

// escapePath takes string and return string with escaped some characters which can be used as a path in the bash
String escapePath(String path) {
  return path.replace(' ', '\\ ')
}

/* groovylint-disable
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral,
  FactoryMethodName, VariableTypeRequired */
void buildGoBinary(String directory, String outputBinary, String packages) {
  timeout(time: 10, unit: 'MINUTES') {
    dir(directory) {
      // sh 'go mod vendor'
      sh "go build -o ${outputBinary} ${packages}"
      sh "chmod +x ${outputBinary}"
    }
  }
}

String shellOutput(String command, boolean silent = false) {
  if (silent) {
    command = '#!/bin/bash +x\n' + command
  }

  return sh(returnStdout: true,
    script: command).trim()
}

Map<String, ?> networkStatistics(String netName, int nodesRetry = 5) {
  if (netName.length() < 1) {
    error('[vegautils.networkStatistics] URL needs to be passed')
  }

  String statisticsJSON = ""
  
  for (int nn=0; nn<nodesRetry; nn++) {
    try {
      networkURL = sprintf('https://n%02d.%s.vega.xyz/statistics', nn, netName)
      statisticsJSON = shellOutput('curl --max-time 3 ' + networkURL)
      break
    } catch(err) {
      println('[vegautils.networkStatistics] Failed to get statistics for node ' + networkURL + ': ' + err.getMessage())
    }
  }

  if (statisticsJSON.length() < 1) {
    error('[vegautils.networkStatistics] Empty response from network statistics for network: ' + netName)
  }
  
  
  try {
    return (readJSON(text: statisticsJSON, returnPojo: true))
  } catch(err) {
    error('[vegautils.networkStatistics] Network statistics JSON is invalid: ' + err.getMessage())
  }

  return [:]
}