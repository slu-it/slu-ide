# Ebooks: left for later

Explored, not decided or built. The core of the reader: [ADR 0165](../adr/0165-ebook-reader.md).

## More formats
- **PDF, FB2, MOBI/AZW**: other readers behind the same `BookPage`; PDF has no reflowable text, MOBI/AZW need a
  converter.
- **DRM**: protected books are refused; removing DRM is not planned.

## Reading
- **Bookmarks** and a place to resume; remembering **font size, zoom or the book-style choice** per book or globally.
- **Sleep timer** for Read Aloud: stop after a time or at the end of the chapter.
- **Prefetching the next chapter's audio** so a chapter change does not pause.
- **Reading TOC entries aloud** as announcements between chapters.
- **Real fixed-layout rendering** (pre-paginated books, comics) instead of reflowed text.
- **A cover view** that shows the cover image before the first chapter.

## Around the book
- **A library or recent-books list** with covers.
- **Annotations and highlights** stored per book.
- **Explorer "Read Aloud" for `.epub`** files without opening the reader first.
