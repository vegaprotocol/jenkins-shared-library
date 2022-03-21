/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */

void doClone(Map config) {
  checkout([
    $class: 'GitSCM',
    branches: [[name: config?.branch]],
    userRemoteConfigs: [[
      url: config?.url,
      credentialsId: config?.credentialsId
  ]]])
}

void call(Map additionalConfig) {
  Map defaultCconfig = [
      directory: '',
      branch: 'main',
      url: '',
      credentialsId: ''
  ]

  Map config = defaultCconfig + additionalConfig

  ['branch', 'url', 'credentialsId'].each { item ->
    if (config[item]?.length() < 1) {
      error('[gitClone] Field config.' + item + ' cannot be empty')
    }
  }

  if (config.directory == '') {
    return doClone(config)
  }

  dir(config.directory) {
    doClone(config)
  }
}

/**
 * Example usage
 */
// gitClone([
//   credentialsId: 'vega-ci-bot',
//   url: 'git@github.com:vegaprotocol/vegacapsule.git',
//   branch: 'main'
// ])

// gitClone([
//   credentialsId: 'vega-ci-bot',
//   url: 'git@github.com:vegaprotocol/vegacapsule.git',
//   branch: 'main',
//   directory: 'abc'
// ])

// gitClone([
//   credentialsId: 'vega-ci-bot',
//   url: 'git@github.com:vegaprotocol/vegacapsule.git',
//   branch: 'main',
//   directory: 'def'
// ])
