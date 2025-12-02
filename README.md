# TapSignal
[English](README.md) | [中文](README_CN.md)


TapSignal is a fork of [Signal](https://github.com/signalapp/Signal-Android). It provides a Tap mode that allows users to send all messages through a new channel instead of the Signal Server.

## Introduction
TapSignal currently supports two modes:

- v2: Based on AWS cloud services
We use AWS S3, Lambda functions, and API Gateway to build a decentralized Signal Server. In short, Lambda functions send messages, API Gateway receives messages, and S3 stores attachments and offline messages. This recreates the message delivery functionality of Signal Server.

- v3: Based on UnifiedPush and IPFS
We use UnifiedPush to send short messages directly. For long messages and attachments, we upload them to IPFS to get a CID, then send the CID via UnifiedPush. For offline messages, we use the message queue provided by UnifiedPush.

Currently, v3 mode private chat is complete. Group chat support is still in progress.

## Download
You can download the APK file from the GitHub [Releases page](https://github.com/koabula/TapSignal/releases/).

## Usage
Both modes require some configuration in Settings. Configure v2 mode in "Tap Config" and v3 mode in "Tap v3 Configuration".
![alt text](./doc/image.png)

### v2 mode
Currently v2 mode only supports AWS. Tencent support is not yet implemented.

For v2 mode, you need:
1. Create an AWS S3 bucket
2. Get an AWS AccessKey

First, create a new S3 bucket in the AWS console. Remember the bucket name and region (like us-west-1).
Then get an AWS AccessKey for the app. You can:

a. Use a full-permission access key. In the AWS console, click your username, select "Security Credentials", and create an AccessKey. Enter this AccessKey in the app.

b. Create a minimal-permission AccessKey:
Use our CloudFormation template [tap-iam-cloudformation](./tap-iam-cloudformation.yaml) to create a minimal-permission credential for the app.

(1) Go to CloudFormation in the AWS console

(2) Click "Create Stack" → "With new resources"

(3) Upload the template file and fill in the parameters (your S3 bucket info)

(4) Wait for deployment to complete

(5) Copy the AccessKeyId and SecretAccessKey from the Outputs tab

(6) Configure this AccessKey in the app

In the v2 mode config screen, click "Deploy Push Service". This takes a few minutes. Do not exit during deployment.

### v3 mode
For v3 mode, you need: a UnifiedPush Distributor, and a Pinata or web3.storage API key (or both).

For UnifiedPush Distributor, we recommend [ntfy](https://unifiedpush.org/users/distributors/ntfy/) and [NextPush](https://unifiedpush.org/users/distributors/nextpush/).
Sunup is not supported yet due to some bugs being fixed.

## Notes
You may see a "Google Play services missing" message. This is normal because we disabled Google FCM service. If you need the app to receive messages in the background, please allow the app to run in the background.
