
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import ru.sb066coder.coroutines.dto.Author
import ru.sb066coder.coroutines.dto.Comment
import ru.sb066coder.coroutines.dto.Post
import ru.sb066coder.coroutines.dto.PostWithComments
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

const val BASE_URL = "http://localhost:9999/api/"

val client = OkHttpClient.Builder()
    .addInterceptor(HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    })
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()

val gson = Gson()


suspend fun getPosts(): List<Post> = makeRequest("slow/posts", object : TypeToken<List<Post>>() {})

suspend fun getComments(id: Long): List<Comment> = makeRequest(
    "slow/posts/$id/comments",
    object : TypeToken<List<Comment>>() {}
)

suspend fun getAuthors(ids: List<Long>): List<Author> {
    return ids.map {
        makeRequest("authors/$it", object : TypeToken<Author>() {})
    }
}

suspend fun <T> makeRequest(endpoint: String, typeToken: TypeToken<T>): T = suspendCoroutine { continuation ->
    Request.Builder()
        .url("$BASE_URL$endpoint")
        .build()
        .let(client::newCall)
        .enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.body?.string().orEmpty().let {
                        gson.fromJson<T>(it, typeToken.type)
                    }
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
}


fun main() {
    val scope = CoroutineScope(EmptyCoroutineContext)

    scope.launch {
        val posts = getPosts()
        val postsWithComments = posts.map {
            async {
                val comments = getComments(it.id)
                PostWithComments(it, comments)
            }
        }.awaitAll()
        val authors: List<Author> = postsWithComments.let {list ->
            val authorIds: HashSet<Long> = hashSetOf()
            list.forEach {post ->
                authorIds.add(post.post.authorId)
                post.comments.forEach {comment ->
                    authorIds.add(comment.authorId)
                }
            }
            async { getAuthors(authorIds.toList()) }
        }.await()
        postsWithComments.forEach {
            println("post #${it.post.id}: ${ it.post.content.take(40) } " +
                    "author name: ${ authors.first { author -> author.id == it.post.authorId }.name }\n")
            it.comments.forEach { comment ->
                println("       comment #${comment.id}: ${ comment.content.take(40) } " +
                        "author name: ${ authors.first { author -> author.id == comment.authorId }.name }\n")
            }
        }
    }
    Thread.sleep(5_000L)
}