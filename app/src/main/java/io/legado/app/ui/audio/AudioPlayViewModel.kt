package io.legado.app.ui.audio

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.App
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.BookHelp
import io.legado.app.model.WebBook
import io.legado.app.service.help.AudioPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioPlayViewModel(application: Application) : BaseViewModel(application) {
    var titleData = MutableLiveData<String>()
    var coverData = MutableLiveData<String>()
    var book: Book? = null
    var inBookshelf = false
    var chapterSize = 0
    var callBack: CallBack? = null
    var durChapterIndex = 0
    var durPageIndex = 0
    var isLocalBook = true
    var webBook: WebBook? = null
    private val loadingChapters = arrayListOf<Int>()

    fun initData(intent: Intent) {
        execute {
            inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            val bookUrl = intent.getStringExtra("bookUrl")
            book = if (!bookUrl.isNullOrEmpty()) {
                App.db.bookDao().getBook(bookUrl)
            } else {
                App.db.bookDao().lastReadBook
            }
            book?.let { book ->
                titleData.postValue(book.name)
                durChapterIndex = book.durChapterIndex
                durPageIndex = book.durChapterPos
                isLocalBook = book.origin == BookType.local
                App.db.bookSourceDao().getBookSource(book.origin)?.let {
                    webBook = WebBook(it)
                }
                val count = App.db.bookChapterDao().getChapterCount(book.bookUrl)
                if (count == 0) {
                    if (book.tocUrl.isEmpty()) {
                        loadBookInfo(book)
                    } else {
                        loadChapterList(book)
                    }
                } else {
                    if (durChapterIndex > count - 1) {
                        durChapterIndex = count - 1
                    }
                    chapterSize = count
                }
            }
            saveRead()
        }
    }

    private fun loadBookInfo(
        book: Book,
        changeDruChapterIndex: ((chapters: List<BookChapter>) -> Unit)? = null
    ) {
        execute {
            webBook?.getBookInfo(book, this)
                ?.onSuccess {
                    loadChapterList(book, changeDruChapterIndex)
                }
        }
    }

    private fun loadChapterList(
        book: Book,
        changeDruChapterIndex: ((chapters: List<BookChapter>) -> Unit)? = null
    ) {
        execute {
            webBook?.getChapterList(book, this)
                ?.onSuccess(Dispatchers.IO) { cList ->
                    if (!cList.isNullOrEmpty()) {
                        if (changeDruChapterIndex == null) {
                            App.db.bookChapterDao().insert(*cList.toTypedArray())
                            chapterSize = cList.size
                        } else {
                            changeDruChapterIndex(cList)
                        }
                    } else {
                        toast(R.string.error_load_toc)
                    }
                }?.onError {
                    toast(R.string.error_load_toc)
                }
        }
    }

    fun loadContent(book: Book, index: Int) {
        if (addLoading(index)) {
            execute {
                App.db.bookChapterDao().getChapter(book.bookUrl, index)?.let { chapter ->
                    BookHelp.getContent(book, chapter)?.let {
                        contentLoadFinish(chapter, it)
                        removeLoading(chapter.index)
                    } ?: download(book, chapter)
                } ?: removeLoading(index)
            }.onError {
                removeLoading(index)
            }
        }
    }

    private fun download(book: Book, chapter: BookChapter) {
        webBook?.getContent(book, chapter, scope = this)
            ?.onSuccess(Dispatchers.IO) { content ->
                if (content.isNullOrEmpty()) {
                    contentLoadFinish(chapter, context.getString(R.string.content_empty))
                    removeLoading(chapter.index)
                } else {
                    BookHelp.saveContent(book, chapter, content)
                    contentLoadFinish(chapter, content)
                    removeLoading(chapter.index)
                }
            }?.onError {
                contentLoadFinish(chapter, it.localizedMessage)
                removeLoading(chapter.index)
            }
    }

    private fun addLoading(index: Int): Boolean {
        synchronized(this) {
            if (loadingChapters.contains(index)) return false
            loadingChapters.add(index)
            return true
        }
    }

    private fun removeLoading(index: Int) {
        synchronized(this) {
            loadingChapters.remove(index)
        }
    }

    private fun contentLoadFinish(chapter: BookChapter, content: String) {
        if (chapter.index == durChapterIndex) {
            callBack?.contentLoadFinish(chapter, content)
        }
    }

    fun changeTo(book1: Book) {
        execute {
            book?.let {
                App.db.bookDao().delete(it.bookUrl)
            }
            withContext(Dispatchers.Main) {

            }
            App.db.bookDao().insert(book1)
            book = book1
            App.db.bookSourceDao().getBookSource(book1.origin)?.let {
                webBook = WebBook(it)
            }
            if (book1.tocUrl.isEmpty()) {
                loadBookInfo(book1) { upChangeDurChapterIndex(book1, it) }
            } else {
                loadChapterList(book1) { upChangeDurChapterIndex(book1, it) }
            }
        }
    }

    private fun upChangeDurChapterIndex(book: Book, chapters: List<BookChapter>) {
        execute {
            durChapterIndex = BookHelp.getDurChapterIndexByChapterTitle(
                book.durChapterTitle,
                book.durChapterIndex,
                chapters
            )
            book.durChapterIndex = durChapterIndex
            book.durChapterTitle = chapters[durChapterIndex].title
            App.db.bookDao().update(book)
            App.db.bookChapterDao().insert(*chapters.toTypedArray())
            chapterSize = chapters.size
        }
    }

    fun moveToPrev() {
        if (durChapterIndex > 0) {
            durChapterIndex--
            book?.durChapterIndex = durChapterIndex
            saveRead()
            book?.let {
                loadContent(it, durChapterIndex)
            }
        }
    }

    fun moveToNext() {
        if (durChapterIndex < chapterSize - 1) {
            durChapterIndex++
            book?.let {
                it.durChapterIndex = durChapterIndex
                saveRead()
                loadContent(it, durChapterIndex)
            }
        } else {
            AudioPlay.stop(context)
        }
    }

    fun saveRead() {
        execute {
            book?.let { book ->
                book.lastCheckCount = 0
                book.durChapterTime = System.currentTimeMillis()
                book.durChapterIndex = durChapterIndex
                book.durChapterPos = durPageIndex
                App.db.bookDao().update(book)
            }
        }
    }

    interface CallBack {
        fun contentLoadFinish(bookChapter: BookChapter, content: String)
    }
}