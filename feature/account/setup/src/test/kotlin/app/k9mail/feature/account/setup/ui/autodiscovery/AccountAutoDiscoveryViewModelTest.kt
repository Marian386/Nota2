package app.k9mail.feature.account.setup.ui.autodiscovery

import app.k9mail.autodiscovery.api.AutoDiscoveryResult
import app.k9mail.core.common.domain.usecase.validation.ValidationError
import app.k9mail.core.common.domain.usecase.validation.ValidationResult
import app.k9mail.core.ui.compose.testing.MainDispatcherRule
import app.k9mail.core.ui.compose.testing.mvi.assertThatAndMviTurbinesConsumed
import app.k9mail.core.ui.compose.testing.mvi.eventStateTest
import app.k9mail.core.ui.compose.testing.mvi.turbinesWithInitialStateCheck
import app.k9mail.feature.account.setup.data.InMemoryAccountSetupStateRepository
import app.k9mail.feature.account.setup.domain.DomainContract
import app.k9mail.feature.account.setup.domain.entity.AccountSetupState
import app.k9mail.feature.account.setup.domain.entity.AutoDiscoverySettingsFixture
import app.k9mail.feature.account.setup.domain.input.BooleanInputField
import app.k9mail.feature.account.setup.domain.input.StringInputField
import app.k9mail.feature.account.setup.ui.FakeAccountOAuthViewModel
import app.k9mail.feature.account.setup.ui.autodiscovery.AccountAutoDiscoveryContract.ConfigStep
import app.k9mail.feature.account.setup.ui.autodiscovery.AccountAutoDiscoveryContract.Effect
import app.k9mail.feature.account.setup.ui.autodiscovery.AccountAutoDiscoveryContract.Error
import app.k9mail.feature.account.setup.ui.autodiscovery.AccountAutoDiscoveryContract.Event
import app.k9mail.feature.account.setup.ui.autodiscovery.AccountAutoDiscoveryContract.State
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AccountAutoDiscoveryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `should reset state when EmailAddressChanged event is received`() = runTest {
        val initialState = State(
            configStep = ConfigStep.PASSWORD,
            emailAddress = StringInputField(value = "email"),
            password = StringInputField(value = "password"),
        )
        val testSubject = createTestSubject(initialState)

        eventStateTest(
            viewModel = testSubject,
            initialState = initialState,
            event = Event.EmailAddressChanged("email"),
            expectedState = State(
                configStep = ConfigStep.EMAIL_ADDRESS,
                emailAddress = StringInputField(value = "email"),
                password = StringInputField(),
            ),
            coroutineScope = backgroundScope,
        )
    }

    @Test
    fun `should change state when PasswordChanged event is received`() = runTest {
        eventStateTest(
            viewModel = createTestSubject(),
            initialState = State(),
            event = Event.PasswordChanged("password"),
            expectedState = State(
                password = StringInputField(value = "password"),
            ),
            coroutineScope = backgroundScope,
        )
    }

    @Test
    fun `should change state when ConfigurationApprovalChanged event is received`() = runTest {
        eventStateTest(
            viewModel = createTestSubject(),
            initialState = State(),
            event = Event.ConfigurationApprovalChanged(true),
            expectedState = State(
                configurationApproved = BooleanInputField(value = true),
            ),
            coroutineScope = backgroundScope,
        )
    }

    @Test
    fun `should change state to password when OnNextClicked event is received, input valid and discovery loaded`() =
        runTest {
            val autoDiscoverySettings = AutoDiscoverySettingsFixture.settings
            val initialState = State(
                configStep = ConfigStep.EMAIL_ADDRESS,
                emailAddress = StringInputField(value = "email"),
            )
            val testSubject = AccountAutoDiscoveryViewModel(
                validator = FakeAccountAutoDiscoveryValidator(),
                getAutoDiscovery = {
                    delay(50)
                    autoDiscoverySettings
                },
                oAuthViewModel = FakeAccountOAuthViewModel(),
                accountSetupStateRepository = InMemoryAccountSetupStateRepository(),
                initialState = initialState,
            )
            val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

            testSubject.event(Event.OnNextClicked)

            val validatedState = initialState.copy(
                emailAddress = StringInputField(
                    value = "email",
                    error = null,
                    isValid = true,
                ),
            )
            assertThat(turbines.stateTurbine.awaitItem()).isEqualTo(validatedState)

            val loadingState = validatedState.copy(
                isLoading = true,
            )
            assertThat(turbines.stateTurbine.awaitItem()).isEqualTo(loadingState)

            val successState = validatedState.copy(
                autoDiscoverySettings = autoDiscoverySettings,
                configStep = ConfigStep.PASSWORD,
                isLoading = false,
            )
            assertThatAndMviTurbinesConsumed(
                actual = turbines.stateTurbine.awaitItem(),
                turbines = turbines,
            ) {
                isEqualTo(successState)
            }
        }

    @Test
    fun `should not change state when OnNextClicked event is received, input valid but discovery failed`() =
        runTest {
            val initialState = State(
                configStep = ConfigStep.EMAIL_ADDRESS,
                emailAddress = StringInputField(value = "email"),
            )
            val discoveryError = Exception("discovery error")
            val testSubject = AccountAutoDiscoveryViewModel(
                validator = FakeAccountAutoDiscoveryValidator(),
                getAutoDiscovery = {
                    delay(50)
                    AutoDiscoveryResult.UnexpectedException(discoveryError)
                },
                oAuthViewModel = FakeAccountOAuthViewModel(),
                accountSetupStateRepository = InMemoryAccountSetupStateRepository(),
                initialState = initialState,
            )
            val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

            testSubject.event(Event.OnNextClicked)

            val validatedState = initialState.copy(
                emailAddress = StringInputField(
                    value = "email",
                    error = null,
                    isValid = true,
                ),
            )
            assertThat(turbines.stateTurbine.awaitItem()).isEqualTo(validatedState)

            val loadingState = validatedState.copy(
                isLoading = true,
            )
            assertThat(turbines.stateTurbine.awaitItem()).isEqualTo(loadingState)

            val failureState = validatedState.copy(
                isLoading = false,
                error = Error.UnknownError,
            )
            assertThatAndMviTurbinesConsumed(
                actual = turbines.stateTurbine.awaitItem(),
                turbines = turbines,
            ) {
                isEqualTo(failureState)
            }
        }

    @Test
    fun `should reset error state and change to password step when OnNextClicked event received when having error`() =
        runTest {
            val initialState = State(
                configStep = ConfigStep.EMAIL_ADDRESS,
                emailAddress = StringInputField(
                    value = "email",
                    isValid = true,
                ),
                error = Error.UnknownError,
            )
            val testSubject = createTestSubject(initialState)

            eventStateTest(
                viewModel = testSubject,
                initialState = initialState,
                event = Event.OnNextClicked,
                expectedState = State(
                    configStep = ConfigStep.PASSWORD,
                    emailAddress = StringInputField(
                        value = "email",
                        isValid = true,
                    ),
                    error = null,
                ),
                coroutineScope = backgroundScope,
            )
        }

    @Test
    fun `should not change config step to password when OnNextClicked event is received and input invalid`() = runTest {
        val initialState = State(
            configStep = ConfigStep.EMAIL_ADDRESS,
            emailAddress = StringInputField(value = "invalid email"),
        )
        val testSubject = AccountAutoDiscoveryViewModel(
            validator = FakeAccountAutoDiscoveryValidator(
                emailAddressAnswer = ValidationResult.Failure(TestError),
            ),
            getAutoDiscovery = { AutoDiscoveryResult.NoUsableSettingsFound },
            oAuthViewModel = FakeAccountOAuthViewModel(),
            accountSetupStateRepository = InMemoryAccountSetupStateRepository(),
            initialState = initialState,
        )

        eventStateTest(
            viewModel = testSubject,
            initialState = initialState,
            event = Event.OnNextClicked,
            expectedState = State(
                configStep = ConfigStep.EMAIL_ADDRESS,
                emailAddress = StringInputField(
                    value = "invalid email",
                    error = TestError,
                    isValid = false,
                ),
            ),
            coroutineScope = backgroundScope,
        )
    }

    @Test
    fun `should save state and emit NavigateNext when OnNextClicked received in password step with valid input`() =
        runTest {
            val initialState = State(
                configStep = ConfigStep.PASSWORD,
                emailAddress = StringInputField(value = "email"),
                password = StringInputField(value = "password"),
            )
            val repository = InMemoryAccountSetupStateRepository()
            val testSubject = createTestSubject(
                initialState = initialState,
                repository = repository,
            )
            val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

            testSubject.event(Event.OnNextClicked)

            assertThat(turbines.stateTurbine.awaitItem()).isEqualTo(
                State(
                    configStep = ConfigStep.PASSWORD,
                    emailAddress = StringInputField(
                        value = "email",
                        error = null,
                        isValid = true,
                    ),
                    password = StringInputField(
                        value = "password",
                        error = null,
                        isValid = true,
                    ),
                    configurationApproved = BooleanInputField(
                        value = null,
                        error = null,
                        isValid = true,
                    ),
                ),
            )

            assertThatAndMviTurbinesConsumed(
                actual = turbines.effectTurbine.awaitItem(),
                turbines = turbines,
            ) {
                isEqualTo(Effect.NavigateNext(isAutomaticConfig = false))
            }

            assertThat(repository.getState()).isEqualTo(
                AccountSetupState(
                    emailAddress = "email",
                    incomingServerSettings = null,
                    outgoingServerSettings = null,
                    authorizationState = null,
                    options = null,
                ),
            )
        }

    @Test
    fun `should not emit NavigateNext when OnNextClicked received in password step with invalid input`() =
        runTest {
            val initialState = State(
                configStep = ConfigStep.PASSWORD,
                emailAddress = StringInputField(value = "email"),
                password = StringInputField(value = "password"),
            )
            val viewModel = AccountAutoDiscoveryViewModel(
                validator = FakeAccountAutoDiscoveryValidator(
                    passwordAnswer = ValidationResult.Failure(TestError),
                ),
                getAutoDiscovery = { AutoDiscoveryResult.NoUsableSettingsFound },
                oAuthViewModel = FakeAccountOAuthViewModel(),
                accountSetupStateRepository = InMemoryAccountSetupStateRepository(),
                initialState = initialState,
            )
            val turbines = turbinesWithInitialStateCheck(viewModel, initialState)

            viewModel.event(Event.OnNextClicked)

            assertThatAndMviTurbinesConsumed(
                actual = turbines.stateTurbine.awaitItem(),
                turbines = turbines,
            ) {
                isEqualTo(
                    State(
                        configStep = ConfigStep.PASSWORD,
                        emailAddress = StringInputField(
                            value = "email",
                            error = null,
                            isValid = true,
                        ),
                        password = StringInputField(
                            value = "password",
                            error = TestError,
                            isValid = false,
                        ),
                        configurationApproved = BooleanInputField(
                            value = null,
                            error = null,
                            isValid = true,
                        ),
                    ),
                )
            }
        }

    @Test
    fun `should emit NavigateBack effect when OnBackClicked event is received`() = runTest {
        val testSubject = createTestSubject()
        val turbines = turbinesWithInitialStateCheck(testSubject, State())

        testSubject.event(Event.OnBackClicked)

        assertThatAndMviTurbinesConsumed(
            actual = turbines.effectTurbine.awaitItem(),
            turbines = turbines,
        ) {
            isEqualTo(Effect.NavigateBack)
        }
    }

    @Test
    fun `should change config step to email address when OnBackClicked event is received in password config step`() =
        runTest {
            val initialState = State(
                configStep = ConfigStep.PASSWORD,
                emailAddress = StringInputField(value = "email"),
                password = StringInputField(value = "password"),
            )
            val testSubject = createTestSubject(initialState)
            val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

            testSubject.event(Event.OnBackClicked)

            assertThatAndMviTurbinesConsumed(
                actual = turbines.stateTurbine.awaitItem(),
                turbines = turbines,
            ) {
                isEqualTo(
                    State(
                        configStep = ConfigStep.EMAIL_ADDRESS,
                        emailAddress = StringInputField(value = "email"),
                    ),
                )
            }
        }

    @Test
    fun `should reset error state when OnBackClicked event received when having error and in email address step`() =
        runTest {
            val initialState = State(
                configStep = ConfigStep.EMAIL_ADDRESS,
                emailAddress = StringInputField(
                    value = "email",
                    isValid = true,
                ),
                error = Error.UnknownError,
            )
            val testSubject = createTestSubject(initialState)

            eventStateTest(
                viewModel = testSubject,
                initialState = initialState,
                event = Event.OnBackClicked,
                expectedState = State(
                    configStep = ConfigStep.EMAIL_ADDRESS,
                    emailAddress = StringInputField(
                        value = "email",
                        isValid = true,
                    ),
                    error = null,
                ),
                coroutineScope = backgroundScope,
            )
        }

    @Test
    fun `should emit NavigateNext effect when OnEditConfigurationClicked event is received`() = runTest {
        val testSubject = createTestSubject()
        val turbines = turbinesWithInitialStateCheck(testSubject, State())

        testSubject.event(Event.OnEditConfigurationClicked)

        assertThatAndMviTurbinesConsumed(
            actual = turbines.effectTurbine.awaitItem(),
            turbines = turbines,
        ) {
            isEqualTo(Effect.NavigateNext(isAutomaticConfig = false))
        }
    }

    private object TestError : ValidationError

    private companion object {
        fun createTestSubject(
            initialState: State = State(),
            repository: DomainContract.AccountSetupStateRepository = InMemoryAccountSetupStateRepository(),
        ): AccountAutoDiscoveryViewModel {
            return AccountAutoDiscoveryViewModel(
                validator = FakeAccountAutoDiscoveryValidator(),
                getAutoDiscovery = {
                    delay(50)
                    AutoDiscoveryResult.NoUsableSettingsFound
                },
                accountSetupStateRepository = repository,
                oAuthViewModel = FakeAccountOAuthViewModel(),
                initialState = initialState,
            )
        }
    }
}
