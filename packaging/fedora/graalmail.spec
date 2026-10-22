# Licensed under the Apache License, Version 2.0 (ObiWindu <Obi.wandu@proton.me>)
Name:           graalmail
Version:        0.1.0
Release:        1%{?dist}
Summary:        Mail client for Gmail, Outlook, Yahoo, Proton Mail and IMAP
License:        MIT
URL:            https://github.com/graalmail/graalmail
BuildArch:      x86_64
Requires:       java-21-openjdk

%description
GraalMail is a desktop mail client supporting multiple providers.

%files
/opt/graalmail
/usr/share/applications/graalmail.desktop
/usr/share/icons/hicolor/256x256/apps/graalmail.png
/usr/bin/graalmail
