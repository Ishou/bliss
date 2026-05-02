package com.bliss.game.api

import com.bliss.game.application.usecases.CreateLobbyUseCase
import com.bliss.game.application.usecases.JoinLobbyUseCase
import com.bliss.game.application.usecases.LeaveLobbyUseCase
import com.bliss.game.application.usecases.RenameSelfUseCase
import com.bliss.game.application.usecases.SetGridConfigUseCase
import com.bliss.game.application.usecases.StartGameUseCase
import com.bliss.game.application.usecases.UpdateCellUseCase

/**
 * Bundle of the use cases the WebSocket route dispatches into. Keeps the
 * route signature compact and makes wiring in [Application.module] explicit.
 * REST endpoints (Wave F PR #9) consume the same aggregate.
 */
data class LobbyUseCases(
    val createLobby: CreateLobbyUseCase,
    val joinLobby: JoinLobbyUseCase,
    val renameSelf: RenameSelfUseCase,
    val setGridConfig: SetGridConfigUseCase,
    val startGame: StartGameUseCase,
    val updateCell: UpdateCellUseCase,
    val leaveLobby: LeaveLobbyUseCase,
)
