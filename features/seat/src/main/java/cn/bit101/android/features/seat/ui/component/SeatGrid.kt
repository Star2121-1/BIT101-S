package cn.bit101.android.features.seat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatStatus

private val AvailableGreen = Color(0xFF4CAF50)
private val OccupiedRed = Color(0xFFF44336)
private val ReservedOrange = Color(0xFFFF9800)
private val SelectedBlue = Color(0xFF1565C0)

@Composable
fun SeatGrid(
    seats: List<Seat>,
    selectedSeatId: String?,
    onSeatClick: (Seat) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 80.dp),
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(4.dp)
    ) {
        items(seats, key = { it.id }) { seat ->
            SeatCell(seat = seat, isSelected = seat.id == selectedSeatId, onClick = { onSeatClick(seat) })
        }
    }
}

@Composable
private fun SeatCell(seat: Seat, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bgColor = when (seat.status) {
        SeatStatus.AVAILABLE -> AvailableGreen.copy(alpha = 0.15f)
        SeatStatus.OCCUPIED -> OccupiedRed.copy(alpha = 0.15f)
        SeatStatus.RESERVED -> ReservedOrange.copy(alpha = 0.15f)
    }
    val borderColor = when {
        isSelected -> SelectedBlue
        seat.status == SeatStatus.AVAILABLE -> AvailableGreen.copy(alpha = 0.4f)
        seat.status == SeatStatus.OCCUPIED -> OccupiedRed.copy(alpha = 0.4f)
        seat.status == SeatStatus.RESERVED -> ReservedOrange.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    val statusColor = when (seat.status) {
        SeatStatus.AVAILABLE -> AvailableGreen
        SeatStatus.OCCUPIED -> OccupiedRed
        SeatStatus.RESERVED -> ReservedOrange
    }
    val statusLabel = when (seat.status) {
        SeatStatus.AVAILABLE -> "可约"
        SeatStatus.OCCUPIED -> "占用"
        SeatStatus.RESERVED -> "预约"
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(bgColor, shape)
            .border(width = if (isSelected) 2.dp else 1.dp, color = borderColor, shape = shape)
            .clickable(enabled = seat.status == SeatStatus.AVAILABLE) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(text = seat.no, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                color = if (isSelected) SelectedBlue else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center, fontSize = 13.sp)
            Text(text = statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor,
                textAlign = TextAlign.Center, fontSize = 11.sp)
        }
    }
}
