[![license](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

# Jenkins libraries and config created and used by Vega

As part of our CI/CD we use Jenkins Server.

Primarly we use Jenkinsfile in all the repositories that require a CI pipeline.

But some of the functionality is common across multiple repositories, this is where we use Jenkins Shared Libraries:
* helper functions
* CI pipelines
* CD pipelines
* Developer Experience pipelines

We also customise look&feel of our Jenkins. We keep that config in here.
