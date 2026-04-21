package dev.brahmkshatriya.echo.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.utils.Serializer.toData

@Entity
data class HistoryEntity(
    @PrimaryKey
    val trackId: String,
    val extensionId: String,
    val playedAt: Long,
    val trackData: String,
) {
    val track by lazy { trackData.toData<Track>().getOrNull() }
}
