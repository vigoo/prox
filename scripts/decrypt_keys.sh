#!/bin/sh -x

openssl aes-256-cbc -K $encrypted_4abe426e5bff_key -iv $encrypted_4abe426e5bff_iv -in travis-deploy-key.enc -out travis-deploy-key -d
chmod 600 travis-deploy-key;
cp travis-deploy-key ~/.ssh/id_rsa;
