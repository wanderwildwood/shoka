# Shelf

A book catalogue for the [Mudita Kompakt](https://mudita.com/products/kompakt/), made for
taking a whole house of books off the shelves and putting them back in order. Scan the
barcode on each book. Shelf finds the book's Dewey number and lists the whole collection in
the order it goes back on the shelf. It will install on any device with Android 11 or later,
Supernote's tablets among them; without a camera, books are added by number, by title search
or by hand.

| | |
|---|---|
| ![Scanning: the camera, other ways to add a book, and the books so far](screenshots/1-scan.png) | ![The collection in Sections, fiction shelved by genre](screenshots/2-shelf.png) |
| ![A book already catalogued, going back on the shelf](screenshots/3-place.png) | ![A book's details, to correct](screenshots/4-edit.png) |

## Cataloguing

- **Scan** the barcode on the back cover. There are no modes: a new book goes into the
  catalogue the moment it is read and is looked up in the background, so you can keep
  scanning without waiting. A book already catalogued is taken to be going back on the
  shelf, and Shelf shows where: its number in shelf order and the books either side
  (with **Add another copy** for when it really is a second one). One beep for a new
  book, two for one already here. A new book shows its place too, once it is looked up.
- **Read the number**, over the camera, for a book with no barcode or a worn one: hold the
  printed ISBN line (on the copyright page, or "ISBN 978-…" on the back) in the box. It is
  read on the phone, and taken only when its check digit is right.
- **Type a number** takes an ISBN, or an LCCN from the copyright page of a book printed
  before ISBNs. It opens on the number keyboard; **Letter keyboard** changes it, for an
  LCCN's letters or an ISBN-10's closing X.
- **Say it instead**, with the letter keyboard's own voice typing (Gboard, FUTO): a title, or a
  number spoken as words ("nine seven eight oh one four…"), which Shelf turns back into digits.
- **Search by title** searches the Library of Congress by title and author, or adds the book by
  hand; so does the **+** in the top bar, for a book with nothing to look it up by.
- A barcode that isn't an ISBN (often a price code on a paperback) is turned away with a
  low tone.
- **Listen**, on a book's page, when [Audiobooks](https://github.com/wanderwildwood/mimidoku)
  has a recording of it (on the card or on its Audiobookshelf server): it opens the book
  there. Audiobooks answers Shelf alone, and only about the book asked for.

Without a connection, books are still scanned and kept. They are looked up as soon as the
connection comes back, and again each time the app is opened.

## Shelf order

Two orders, both [Dewey](https://en.wikipedia.org/wiki/Dewey_Decimal_Classification), the
scheme most libraries around the world use, switched at the top of the list.

**Dewey** is the full number, read as a decimal (813.52 before 813.54), with books that
share a number standing by author, as a library's do. Books with no Dewey number come
after the numbered ones, by author and then by title.

**Sections** is for a house rather than a library: no labels on the spines, and an order
you can keep by eye. Fiction comes first: general fiction, then Crime & mystery, Science
fiction, Fantasy, Horror, Romance, Historical fiction and Children's. Then come Dewey's ten
hundreds (100 Philosophy, 500 Science, 800 Literature and so on). Within each section, books
stand by author and then by title. A label on the edge of each shelf is enough. A book
counts as fiction when its Dewey number is a fiction number (813, 823, 891.73 and the like),
and its genre comes from the catalogue's subject headings. **Fiction** and **Shelf** in a
book's details overrule either. In this order, a scanned book's place names its
section and the books either side of it within that section.

**To fix** narrows the list to the books still to be looked up or that no catalogue knew,
and the books with no call number. Tap any book to correct it, change the number of copies,
look it up again, or delete it.

## Where the details come from

The [Library of Congress catalogue](https://www.loc.gov/) first, then
[Open Library](https://openlibrary.org/) for anything it lacks. Neither needs an account.
When neither gives a Dewey number, or only Open Library knows the ISBN, Shelf looks for
another Library of Congress edition of the same title and author and takes its number
(Dewey numbers the work, not the printing) and its form of the author's name. Whether a
book is fiction comes from the catalogue when it says so, and from the Dewey number when
it doesn't. A novel's genre comes from its subject headings ("Detective and mystery
stories", "Science fiction" and the like), and its audience code for children's books.

## Keeping the list

The catalogue lives on the phone. **Settings (the cog) → Save as a spreadsheet** writes it out in
shelf order as a CSV (with each book's section, in the Sections order), and **Add from a
spreadsheet** reads one back in.

With a [Nextcloud](https://nextcloud.com/) account, **Settings → Nextcloud** keeps the
catalogue in a folder there (`Books` unless you choose another), as `catalogue.json` with
a `shelf-order.csv` beside it. Every phone signed in to the same folder keeps the same
list, so two people can scan different rooms at once: while Shelf is open it asks the
server every 15 seconds whether the catalogue has changed (a few hundred bytes, not the
catalogue), so a book scanned on one phone shows on the other within about twenty seconds.
Nothing is asked while the app is closed. Sign in through the server's own
page, or with an app password: make one in Nextcloud's security settings on a computer,
press **Show QR code for mobile apps**, and scan it with **Scan a QR code**. Pulling a book
list down from its top syncs too, and a line says "Syncing…" and then "Up to date"; with no
Nextcloud set up it does nothing.

## Building

```
./gradlew assembleDebug
```

A release build needs a keystore at `signing/signing.keystore` with a matching
`signing/signing.properties`. There is no fallback key in this repository: without one, a
release build comes out unsigned rather than wrongly signed.

`./gradlew testDebugUnitTest` runs the tests: ISBN and LCCN checking, call-number sort
order, the Sections order, merging two phones' catalogues, and reading Nextcloud's sign-in QR code.

## Getting it, and keeping it

Download <https://github.com/wanderwildwood/shoka/releases/latest/download/shoka.apk>
and sideload it. That address always points at the newest release, and every release
publishes a `.sha256` beside the APK if you would rather check than trust.

For updates without doing this by hand, add this repository to
[Obtainium](https://github.com/ImranR98/Obtainium):

    https://github.com/wanderwildwood/shoka

## Licence

GPL-3.0-only. See [LICENSE](LICENSE).

Copyright (C) 2026 wander wildwood

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, version 3.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If
not, see <https://www.gnu.org/licenses/>.

Barcodes are read with [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp), Apache 2.0.

Printed ISBNs are read with [Tesseract](https://github.com/tesseract-ocr/tesseract), Apache 2.0,
through [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android), Apache 2.0, which
carries [Leptonica](http://www.leptonica.org/) (BSD 2-clause), libjpeg (the IJG licence) and
libpng (the libpng licence). The model is the English one from
[tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast), Apache 2.0, with its word lists
taken out, since only the number is read.
