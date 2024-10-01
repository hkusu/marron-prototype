package io.github.hkusu.marron

import io.github.hkusu.marron.Store.EventEmit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ViewModel で保持（ KMP プロジェクトでない場合は ViewModel に Store を implement してもよし
class MainStore(
    // UseCase や Repository を inject
    //   getHogeListUseCase: GetHogeListUseCase,
    //   setgFugaUseCase: SetFugaUseCase,
    coroutineScope: CoroutineScope, // 基本は viewModelScope を渡す想定
) : DefaultStore<MainState, MainAction, MainEvent>(
    initialState = MainState.Initial,
    enterAction = MainAction.Enter,
    exitAction = MainAction.Exit,
    middlewares = listOf(
        object : Middleware<MainState, MainAction, MainEvent> {
            override fun onDispatched(state: MainState, action: MainAction) {
            }

            override fun onEventEmitted(state: MainState, action: MainAction, event: MainEvent) {
                println("event emitted")
            }
        },
    ),
    coroutineScope = coroutineScope,
) {
    override suspend fun onDispatched(state: MainState, action: MainAction, emit: EventEmit<MainEvent>): MainState = when (state) {
        MainState.Initial -> when (action) {
            MainAction.Enter -> {
                // すぐさま Loading に
                MainState.Loading
            }

            else -> null
        }

        MainState.Loading -> when (action) { // Compose でローディング中の旨の画面を表示
            MainAction.Enter -> {
                // UseCase や Repository からデータ取得
                delay(5_000)
                // データを読み終わったら Stable に
                MainState.Stable(listOf())
            }

            else -> null
        }

        is MainState.Stable -> when (action) { // Compose で state.dataList のデータを画面へ描画する
            MainAction.Enter -> {
                // イベント発行例
                emit(MainEvent.ShowToast("データがロードされました"))
                null
            }

            is MainAction.Click -> {
                // state の更新は data class の copy で
                state.copy(clickCounter = state.clickCounter + 1)
            }

            else -> null
        }

    } ?: state
}

// MainStore 等を状況に応じてモックできるようにする
interface Store<S : State, A : Action, E : Event> {
    val state: StateFlow<S>

    val event: Flow<E>

    val currentState: S

    fun dispatch(action: A)

    suspend fun onDispatched(state: S, action: A, emit: EventEmit<E>): S

    fun dispose()

    fun collect(onState: OnState<S>, onEvent: OnEvent<E>): Job

    fun interface EventEmit<E> {
        suspend operator fun invoke(event: E)
    }

    fun interface OnState<S> {
        suspend operator fun invoke(event: S)
    }

    fun interface OnEvent<E> {
        suspend operator fun invoke(event: E)
    }
}

open class DefaultStore<S : State, A : Action, E : Event>(
    initialState: S,
    private val enterAction: A?,
    private val exitAction: A?,
    private val middlewares: List<Middleware<S, A, E>> = emptyList(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : Store<S, A, E> {
    private val _state: MutableStateFlow<S> = MutableStateFlow(initialState)
    override val state: StateFlow<S> = _state // Compose で購読

    private val _event: MutableSharedFlow<E> = MutableSharedFlow()
    override val event: Flow<E> = _event // Compose で購読

    override val currentState: S get() = _state.value

    private val mutex = Mutex()

    init {
        enterAction?.let { dispatch(it) }
    }

    override fun dispatch(action: A) { // ユーザによる操作. Compose の画面から叩く
        coroutineScope.launch {
            mutex.withLock {
                changeState(action)
            }
        }
    }

    override suspend fun onDispatched(state: S, action: A, emit: Store.EventEmit<E>): S {
        TODO("You should override")
    }

    // viseModelScope のような auto close の CoroutinesScope 以外の場合に利用
    override fun dispose() {
        coroutineScope.cancel()
    }

    override fun collect(onState: Store.OnState<S>, onEvent: Store.OnEvent<E>): Job {
        return coroutineScope.launch {
            launch { state.collect { onState(it) } }
            launch { event.collect { onEvent(it) } }
        }
    }

    private suspend fun changeState(action: A) {
        val prevState = _state.value

        val nextState = onDispatched(prevState, action) { event ->
            _event.emit(event)
            middlewares.forEach {
                it.onEventEmitted(prevState, action, event)
            }
        }

        if (prevState::class.qualifiedName != nextState::class.qualifiedName) {
            exitAction?.let { exitAction ->
                onDispatched(prevState, exitAction) { event ->
                    _event.emit(event)
                    middlewares.forEach {
                        it.onEventEmitted(prevState, action, event)
                    }
                }
            }
        }

        _state.update { nextState }

        if (prevState::class.qualifiedName != nextState::class.qualifiedName) {
            enterAction?.let { changeState(it) }
        }
    }
}

sealed interface Contract

sealed interface State : Contract
sealed interface Action : Contract
sealed interface Event : Contract

sealed interface MainState : State {
    data object Initial : MainState
    data object Loading : MainState
    data class Stable(
        val dataList: List<String>,
        val clickCounter: Int = 0,
    ) : MainState
}

sealed interface MainAction : Action {
    data object Enter : MainAction
    data object Exit : MainAction
    data class Click(val id: Long) : MainAction
}

sealed interface MainEvent : Event {
    data class ShowToast(val message: String) : MainEvent
}

interface Middleware<S : State, A : Action, E : Event> {
    fun onDispatched(state: S, action: A)
    fun onEventEmitted(state: S, action: A, event: E)
}
