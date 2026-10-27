# Privacy

Shelf has no account of its own and no server of its own. It sends nothing anywhere except
as described here.

**The camera** is used only to read barcodes, an ISBN printed as text, and the QR code Nextcloud shows for signing in, on the phone. No picture is kept or sent.

**Looking a book up** sends its ISBN or LCCN, or the title and author you search for, to the
Library of Congress (`lx2.loc.gov`) and to Open Library (`openlibrary.org`). The Library of
Congress serves this catalogue over plain HTTP only, so what is sent to it is not encrypted.
Nothing else about you or your other books goes with it.

**Nextcloud**, only if you sign in: the catalogue is written to a folder on your own
server. While the app is on screen it asks that server every 15 seconds whether the catalogue
has changed, so books another phone adds show up; it asks nothing while the app is closed. The app password that sign-in creates is kept in the app's private storage on the
phone; revoke it from Nextcloud's security settings at any time.

The catalogue itself is kept in the app's private storage. The app does not back it up, and
uninstalling it removes it, along with the Nextcloud sign-in. The copy on Nextcloud stays
there.

**Audiobooks**, when it is installed: a book's page asks it, on the phone, whether it has
that book (by title and author), to show **Listen**. Nothing is sent anywhere.

The About dialog's last line opens a web address in your browser, and only when you press it.
