#!/usr/bin/env bash

set -eo pipefail
IFS=$'\n\t'

rm -rf virtualenv
virtualenv --setuptools --no-site-packages -p python2.7 virtualenv
