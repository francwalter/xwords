#!/bin/bash

set -e -u

APP_ID=de.fcw.android.xw4

APK_PATH=$(adb shell pm path $APP_ID)
APK_PATH=${APK_PATH/package:/}
adb pull $APK_PATH
