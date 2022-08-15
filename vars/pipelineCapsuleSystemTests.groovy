void call() {
  println('pipelineCapsuleSystemTests params: ' + params)
  wrapper = 'common/system-tests-wrapper'
  childs = []
  // this is default scenario for smoke test, but it will require changing for other types
  scenario = [
    'PR': [
      'smoke a-g': [
        pytestArgs: "--ignore-glob 'tests/[h-zH-Z]*/**.py'",
        mark: 'smoke'
      ],
      'smoke h-m': [
        pytestArgs: "--ignore-glob 'tests/[a-gA-Gn-zN-Z]*/**.py'",
        mark: 'smoke'
      ],
      'smoke n-z': [
        pytestArgs: "--ignore-glob 'tests/[a-mA-M]*/**.py'",
        mark: 'smoke'
      ],
      'network_infra_smoke a-z': [
        mark: 'network_infra_smoke'
      ],
    ],
    'NIGHTLY': [
      'full a-e': [
        pytestArgs: '--ignore-glob tests/[f-zF-Z]*/**.py',
        mark: 'full',
      ],
      'full g-p': [
        pytestArgs: '--ignore-glob tests/[a-eA-Er-zR-Z]*/**.py',
        mark: 'full',
      ],
      'full r': [
        pytestArgs: '--ignore-glob tests/[a-pA-Ps-zS-Z]*/**.py',
        mark: 'full',
      ],
      'full s': [
        pytestArgs: '--ignore-glob tests/[a-rA-Rt-zT-Z]*/**.py',
        mark: 'full',
      ],
      'full t-z': [
        pytestArgs: '--ignore-glob tests/[a-sA-S]*/**.py',
        mark: 'full',
      ],
      'network_infra a-n': [
        pytestArgs: '--ignore-glob tests/[o-zO-Z]*/**.py',
        mark: 'network_infra',
        capsuleConfig: 'capsule_config_network_infra.hcl'
      ],
      'network_infra o-u w-z': [
        pytestArgs: '--ignore-glob tests/[a-nA-Nv-vV-V]*/**.py',
        mark: 'network_infra',
        capsuleConfig: 'capsule_config_network_infra.hcl'
      ],
      'network_infra validators a-p': [
        pytestArgs: '--ignore-glob tests/validators/[r-zR-Z]*.py',
        mark: 'network_infra',
        capsuleConfig: 'capsule_config_network_infra.hcl'
      ],
      'network_infra validators r-z': [
        pytestArgs: '--ignore-glob tests/validators/[a-pA-P]*.py',
        mark: 'network_infra',
        capsuleConfig: 'capsule_config_network_infra.hcl'
      ],
    ]
  ][params.SCENARIO]
  pipeline {
    agent none
    stages {
      stage('Call tests') {
        steps {
          script {
            parallel scenario.collectEntries { name, testSpec ->
              [
                (name): {
                  childParams = collectParams()
                  if (testSpec.pytestArgs) {
                    childParams += [string(name: 'TEST_EXTRA_PYTEST_ARGS', value: testSpec.pytestArgs)]
                  }
                  if (testSpec.mark) {
                    childParams += [string(name: 'SYSTEM_TESTS_TEST_MARK', value: testSpec.mark)]
                  }
                  if (testSpec.capsuleConfig) {
                    childParams += [string(name: 'CAPSULE_CONFIG', value: testSpec.capsuleConfig)]
                  }
                  childs.add(
                    build(
                      job: wrapper,
                      parameters: childParams,
                    )
                  )
                }
              ]
            }
          }
        }
      }
      stage('Collect results') {
        agent any
        steps {
          sh "mkdir -p results"
          script {
            childs.each {
              copyArtifacts(
                  filter : "build/test-reports/system-test-results.xml",
                  flatten: true,
                  projectName : wrapper,
                  // job object is in list, it's call for getNumber()
                  selector: specific(it.number as String)
              )
              sh "mv system-tests-results.xml results/system-tests-results-${it.number}.xml"
            }
          }
          junit(
            checksName: 'System Tests',
            testResults: 'results/**.xml',
            skipMarkingBuildUnstable: false,
            skipPublishingChecks: false,
          )
        }
        post {
          always {
            cleanWs()
          }
        }
      }
    }
  }
}

