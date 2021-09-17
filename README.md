[![license](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

# Jenkins libraries and config created and used by Vega

As part of our CI/CD, we use Jenkins Server.

Primarily we use Jenkinsfile in all the repositories that require a CI pipeline.

But some of the functionality is common across multiple repositories, and this is where we use Jenkins Shared Libraries:
* helper functions
* CI pipelines
* CD pipelines
* Developer Experience pipelines

We also customise look&feel of our Jenkins. We keep that config in here.

## Not everything is Public yet

Just so you know, some of the Jenkins Libraries are used by or based on Private repositories, which you don't see. We are working hard to get all our repositories Public, but we are not there yet.
