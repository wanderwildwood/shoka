# Privacy

Shelf has no account of its own and no server of its own. It sends nothing anywhere except
as described here.

**The camera** is used only to read barcodes, an ISBN printed as text, and the QR code Nextcloud shows for signing in, on the phone. No picture is kept or sent.

**Looking a book up** sends its ISBN or LCCN, or the title and author you search for, to the
Library of Congress (`lx2.loc.gov`) and to Open Library (`openlibrary.org`). The Library of
Congress serves this catalog over plain HTTP only, so what is sent to it is not encrypted.
When those two have no Dewey number for a book, its ISBN alone also goes to that country's
national library: Norway's (`api.nb.no`) for an ISBN starting 978-82, Germany's
(`services.dnb.de`) for 978-3, Sweden's LIBRIS (`libris.kb.se`) for 978-91. When no catalog
knows the ISBN at all, it goes to Google Books (`www.googleapis.com`). Nothing else about you
or your other books goes with it.

**Nextcloud**, only if you sign in: the catalog is written to a folder on your own
server. While the app is on screen it asks that server every 15 seconds whether the catalog
has changed, so books another phone adds show up; it asks nothing while the app is closed. The app password that sign-in creates is kept in the app's private storage on the
phone; revoke it from Nextcloud's security settings at any time.

The catalog itself is kept in the app's private storage. The app does not back it up, and
uninstalling it removes it, along with the Nextcloud sign-in. The copy on Nextcloud stays
there.

**Audio Reader**, when it is installed: a book's page asks it, on the phone, whether it has
that book (by title and author), to show **Listen**. Nothing is sent anywhere.

About opens web addresses in your browser (the source, the site and the donation page) only when you press them.

<!-- shared-about privacy section -->
## Who we are

Shelf is made by wander wildwood, an independent developer: one person, not a company. wander wildwood is the data controller for anything the app itself handles, and that is very little. What you put in Shelf stays on your phone. The only things that go out are the lookups described above: a book's ISBN or LCCN, or the title and author you search for, sent to the Library of Congress and Open Library, and an ISBN to the national libraries and Google Books as above; and, only if you sign in, the catalog written to your own Nextcloud server. The developer receives nothing, so the developer holds no data about you.

## How to contact us

Write to email@wanderthe.dev about your data, this policy or anything else. The same address is in the app, under About.

## Your rights

If you are in the EU, the EEA or the UK, the GDPR gives you the right to:

- see the personal data held about you (access)
- have it corrected (rectification) or deleted (erasure)
- limit how it is used (restriction)
- object to its use (objection)
- get a copy to take elsewhere (portability)
- withdraw your consent at any time, where something rests on it
- complain to a data protection supervisory authority, for example the one where you live

Similar laws elsewhere, such as the CCPA in California, give you the right to know, delete and correct, and to opt out of the sale or sharing of your data. Nothing from Shelf is sold or shared.

Since Shelf keeps what it stores only on your phone, you can exercise most of these rights yourself: edit or delete a book in the app, save the catalog as a spreadsheet, clear the app's data in Android's settings, or uninstall the app, which deletes all of it. For anything you cannot do yourself, write to the address above.

Shelf connects to the Library of Congress, Open Library, the national libraries of Norway, Germany and Sweden, and Google Books. They are separate from this app and from its developer, and what they receive is covered by their own privacy policies; ask them about what they hold.
If you point Shelf at a server of your own, what that server keeps is yours to manage.
<!-- /shared-about privacy section -->
