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

Map<String, ?> networkStatistics(Map args=[:]) {
  String netName = args.get("netName", "")
  int nodesRetry = args.get("nodesRetry", 5)
  List nodesList = args.get("nodesList", [])

  if (netName.length() < 1 && nodesList.size < 1) {
    error('[vegautils.networkStatistics] netName or nodesList parameter must be pass')
  }

  String statisticsJSON = ""
  // network name is provided, let's generate list of nodes for the given netName
  if (netName.length() > 0) {
    nodesList = []
    for (int nn=0; nn<nodesRetry; nn++) {
        nodesList.add(sprintf('https://n%02d.%s.vega.rocks/statistics', nn, netName))
      }
  }

  for (node in nodesList) {
    try {
      nodeURL = node
      if (!node.startsWith('http')) {
          nodeURL = 'https://' + node
      }
      if (!node.endsWith('/statistics')) {
        nodeURL = nodeURL + '/statistics'
      }
      statisticsJSON = shellOutput('curl --max-time 3 ' + nodeURL)
      break
    } catch(err) {
      println('[vegautils.networkStatistics] Failed to get statistics for node ' + nodeURL + ': ' + err.getMessage())
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


// returns 1 if a > b, 0 if a == b, -1 if a < b
// examples:
// semVerCompare('v0.66.1', 'v0.66.2')
// semVerCompare('1.0.1', 'v0.66.2')
int semVerCompare(String a, String b) {
    // leave only dots and numbers
    semVerA = a.replaceAll(/[^\d\.]/, '')
    semVerB = b.replaceAll(/[^\d\.]/, '')

    List verA = semVerA.tokenize('.')
    List verB = semVerB.tokenize('.')
    int commonIndices = Math.min(verA.size(), verB.size())

    for (int i = 0; i < commonIndices; ++i) {
        int numA = verA[i].toInteger()
        int numB = verB[i].toInteger()

        if (numA > numB) {
            return 1
        } else if (numA < numB) {
            return -1
        }
        continue
    }

    return 0
}

// We have two credentials to avoid github rate limits. We are returning random credentials from given list
String getVegaCiBotCredentials() {
  // change this if hitting any rate limits. -ci-bot-artifacts-2 is for argocd ATM
  List credentialNames = [
    // "github-vega-ci-bot-artifacts",
    "github-vega-ci-bot-artifacts-2",
  ]
  Collections.shuffle credentialNames

  return credentialNames.first()
}

def dockerCleanup() {
  sh label: 'docker volume prune',
  returnStatus: true,  // ignore exit code
  script: '''#!/bin/bash -e
      docker volume prune --force
  '''
}



/*
Example usage: 

print(generateJUnitReport([
    [
        name: "Some suite",
        testCases: [
            [
                name: "Test1",
                className: "class.One",
                time: 1.5,
            ],
            [
                name: "Test2",
                className: "class.Two",
                time: 2.5,
                error: [name: "Some Error", type: "SomeType", description: "dsadasfdasasdas"]
            ],
        ]
    ],
    [
        name: "Some suite 2",
        testCases: [
            [
                name: "Test1",
                className: "class.One",
                time: 3.5,
                failure: [name: "Some Failure", type: "SomeType", description: "dsadasfdasasdas"]
            ],
            [
                name: "Test2",
                className: "class.Two",
                time: 5.5,
            ],
        ]
    ]
]))
*/
String generateJUnitReport(List testSuites) {
  // Compute tests times
  
  String defaultTestName = 'unknown test'
  String defaultClassName = 'unknown.class'
  String defaultErrorMessage = 'unknown message'
  String defaultErrorType = 'UnknownType'
  
  List suitesTime = testSuites.collect { suite -> suite.testCases.collect { test -> return test["time"] ?: 0.0 }.sum() }
  float totalTime = suitesTime.sum()
  List suitesErrors = testSuites.collect { suite -> suite.testCases.collect { test -> return test.containsKey("error") ? 1 : 0 }.sum() }
  int totalErrors = suitesErrors.sum()
  List suitesFailures = testSuites.collect { suite -> suite.testCases.collect { test -> return test.containsKey("failure") ? 1 : 0 }.sum() }
  int totalFailures = suitesFailures.sum()
  int totalTests = testSuites.collect { suite -> return suite.testCases.size() }.sum()
  
  
  List<String> result = [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<testsuites time="' + totalTime + ' tests="' + totalTests + '" failures="' + totalFailures + '" errors="' + totalErrors + '">',
  ]
  
  for (int idx=0; idx<testSuites.size(); idx++) {
      result << '  <testsuite name="' + testSuites[idx].name + '" time="' + suitesTime[idx] + '" tests="' +  testSuites[idx].testCases.size() + '" failures="' + suitesFailures[idx] + '" errors="' + suitesErrors[idx] + '">'
      
      for (testCase in testSuites[idx].testCases) {
          if (testCase.containsKey("error") || testCase.containsKey("failure")) {
            result << '    <testcase name="' + (testCase.name ?: defaultTestName) + '" classname="' + (testCase.className ?: defaultClassName) + '" time="' + (testCase.time ?: 0.0) + '">'
            if (testCase.containsKey("error")) {
                result << '      <error message="' + (testCase.error.description ?: defaultErrorMessage) + '" type="' + (testCase.error.type ?: defaultErrorType) + '">'
                result << '        ' +  (testCase.error.description ?: 'No description')
                result << '      </error>'
            }
            
            if (testCase.containsKey("failure")) {
                result << '      <failure message="' + (testCase.failure.description ?: defaultErrorMessage) + '" type="' + (testCase.failure.type ?: defaultErrorType) + '">'
                result << '        ' +  (testCase.failure.description ?: 'No description')
                result << '      </failure>'
            }
            
            result << '    </testcase>'
          } else {
              result << '    <testcase name="' + (testCase.name ?: defaultTestName) + '" classname="' + (testCase.className ?: defaultClassName) + '" time="' + (testCase.time ?: 0.0) + '" />'
          }
      }
      result << '  </testsuite>'
  }
  result << '</testsuites>'
    
  return result.join('\n')
}