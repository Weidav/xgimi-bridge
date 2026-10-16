FROM --platform=linux/amd64 mcr.microsoft.com/devcontainers/java:17-bookworm

ENV DEBIAN_FRONTEND=noninteractive

# Install utilities needed by Android SDK tools
RUN apt-get update && apt-get install -y --no-install-recommends \
    unzip \
    wget \
    curl \
    lib32z1 \
    && rm -rf /var/lib/apt/lists/*

# Set up ANDROID_SDK_ROOT and PATH (including build-tools)
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV BUILD_TOOLS_VERSION=34.0.0
ENV PLATFORM_VERSION=android-34
ENV PATH="${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${ANDROID_SDK_ROOT}/build-tools/${BUILD_TOOLS_VERSION}"

# Install Android Command Line Tools
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Install platform-tools (adb), platforms (android.jar), and build-tools (aapt2, d8, apksigner, zipalign)
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" "platforms;${PLATFORM_VERSION}" "build-tools;${BUILD_TOOLS_VERSION}" && \
    chmod -R 777 ${ANDROID_SDK_ROOT}
