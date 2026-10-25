# Licensed under the Apache License, Version 2.0 (ObiWindu <Obi.wandu@proton.me>)
%ifarch aarch64
%global graaltararch arm64
%else
%global graaltararch amd64
%endif

Name:           graalmail
Version:        0.1.0
Release:        1%{?dist}
Summary:        Mail client for Gmail, Outlook, Yahoo, Proton Mail and IMAP
License:        Apache-2.0
URL:            https://github.com/graalmail/graalmail
Source0:        graalmail-%{version}-linux-%{graaltararch}.tar.gz
Requires:       gtk3, libXtst, libXrender

%description
GraalMail is a desktop mail client supporting multiple email providers
including Gmail, Microsoft Outlook/365, Yahoo Mail, Proton Mail (via Bridge),
and generic IMAP/SMTP servers. It features end-to-end encryption for local
storage, offline support, and a modern JavaFX-based user interface.

%prep
# The release archive contains the self contained application image.
%setup -q -n GraalMail

%build
# The application image is built externally with Gradle and jpackage.

%install
rm -rf %{buildroot}
install -d %{buildroot}/opt/graalmail
cp -a %{_builddir}/GraalMail/. %{buildroot}/opt/graalmail/

install -d %{buildroot}/usr/share/applications
cat > %{buildroot}/usr/share/applications/graalmail.desktop <<'EOF'
[Desktop Entry]
Type=Application
Version=1.0
Name=GraalMail
GenericName=Mail Client
Comment=Read and send mail with Gmail, Outlook, Yahoo, Proton Mail and generic IMAP accounts
Exec=GraalMail %u
Icon=graalmail
Terminal=false
Categories=Network;Email;
Keywords=mail;email;imap;smtp;
StartupWMClass=GraalMail
EOF

install -Dm644 %{_builddir}/GraalMail/lib/GraalMail.png \
    %{buildroot}/usr/share/icons/hicolor/256x256/apps/graalmail.png

install -d %{buildroot}/usr/bin
ln -s /opt/graalmail/bin/GraalMail %{buildroot}/usr/bin/graalmail

%files
/opt/graalmail
/usr/share/applications/graalmail.desktop
/usr/share/icons/hicolor/256x256/apps/graalmail.png
/usr/bin/graalmail

%changelog
* Fri Oct 23 2026 ObiWindu <Obi.wandu@proton.me> - 0.1.0-1
- Initial release
