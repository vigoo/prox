#!/bin/sh

set -e

git config --global user.email "daniel.vigovszky@gmail.com"
git config --global user.name "Daniel Vigovszky"
git config --global push.default simple

sbt publishMicrosite
