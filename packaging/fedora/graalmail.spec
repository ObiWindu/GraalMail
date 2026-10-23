# Licensed under the Apache License, Version 2.0 (ObiWindu <Obi.wandu@proton.me>)
Name:           graalmail
Version:        0.1.0
Release:        1%{?dist}
Summary:        Mail client for Gmail, Outlook, Yahoo, Proton Mail and IMAP
License:        MIT
URL:            https://github.com/graalmail/graalmail
BuildArch:      x86_64
Requires:       java-21-openjdk, gtk3, libXtst, libXrender

%description
GraalMail is a desktop mail client supporting multiple email providers
including Gmail, Microsoft Outlook/365, Yahoo Mail, Proton Mail (via Bridge),
and generic IMAP/SMTP servers. It features end-to-end encryption for local
storage, offline support, and a modern JavaFX-based user interface.

%prep
# The source should be the distribution zip
%setup -q -n graalmail-%{version}

%build
# Build is done externally via Gradle
# Nothing to build here

%install
rm -rf %{buildroot}

# Create directory structure
mkdir -p %{buildroot}/opt/graalmail
mkdir -p %{buildroot}/usr/share/applications
mkdir -p %{buildroot}/usr/share/icons/hicolor/256x256/apps
mkdir -p %{buildroot}/usr/bin

# Copy application files
cp -r graalmail/* %{buildroot}/opt/graalmail/

# Install desktop file and icon from source
cp %{SOURCE1} %{buildroot}/usr/share/applications/graalmail.desktop
cp %{SOURCE2} %{buildroot}/usr/share/icons/hicolor/256x256/apps/graalmail.png

# Create symlink
ln -s /opt/graalmail/bin/graalmail %{buildroot}/usr/bin/graalmail

%files
/opt/graalmail
/usr/share/applications/graalmail.desktop
/usr/share/icons/hicolor/256x256/apps/graalmail.png
/usr/bin/graalmail

%changelog
* Mon Oct 23 2026 ObiWindu <Obi.wandu@proton.me> - 0.1.0-1
- Initial release