#!/bin/sh

set -u -e

APPMK=./jni/Application.mk
TMP_MK=/tmp/tmp_$$_Application.mk
XWORDS_DEBUG_ARMONLY=${XWORDS_DEBUG_ARMONLY:-""}
XWORDS_DEBUG_X86ONLY=${XWORDS_DEBUG_X86ONLYx:-""}

echo "# Generated by $0; do not edit!!!" > $TMP_MK

if [ "$1" = "release" ]; then
    echo "APP_ABI := armeabi x86" >> $TMP_MK
elif [ -n "$XWORDS_DEBUG_ARMONLY" ]; then
    echo "APP_ABI := armeabi" >> $TMP_MK
elif [ -n "$XWORDS_DEBUG_X86ONLY" ]; then
    echo "APP_ABI :=  x86" >> $TMP_MK
else
    echo "APP_ABI := armeabi x86" >> $TMP_MK
fi

# Now replace the existing file, but only if it's different.  Touching
# it causes the library to be completely rebuilt, so avoid that if
# possible!

if [ ! -f $APPMK ]; then
    cp $TMP_MK $APPMK
elif ! diff $APPMK $TMP_MK; then
    cp $TMP_MK $APPMK
fi
rm -f $TMP_MK