<script lang="ts">
	import { ScanLine } from '@lucide/svelte';
	import { post } from '$lib/api';
	import { deviceLabel } from '$lib/push';
	import Button from '$lib/ui/Button.svelte';

	// Вход по коду (0.7): на устройстве, где уже вошли, – «Профиль → Вход и безопасность → Показать
	// код»; здесь – отсканировать его QR камерой или ввести 6 цифр. Пароль не нужен.
	let { onsuccess }: { onsuccess: () => void } = $props();

	let pin = $state('');
	let busy = $state(false);
	let error = $state('');
	let scanOpen = $state(false);
	const digits = $derived(pin.replace(/\D/g, ''));

	async function submit(e?: SubmitEvent) {
		e?.preventDefault();
		if (digits.length !== 6 || busy) return;
		busy = true;
		error = '';
		try {
			await post(
				'/api/auth/link/redeem',
				{ pin: digits, device: deviceLabel() },
				{ anonymous: true }
			);
			onsuccess();
		} catch (err) {
			error = err instanceof Error ? err.message : 'Код не подошёл';
		} finally {
			busy = false;
		}
	}

	// Ввели шесть цифр – входим сразу, без кнопки.
	$effect(() => {
		if (digits.length === 6) submit();
	});
</script>

<form class="code-login" onsubmit={submit}>
	<ol class="how">
		<li>
			На телефоне или компьютере, где вы уже вошли: <b
				>Профиль → Вход и безопасность → Показать код</b
			>
		</li>
		<li>Отсканируйте QR-код здесь или введите 6 цифр</li>
	</ol>
	<Button onclick={() => (scanOpen = true)}><ScanLine size={18} /> Сканировать QR-код</Button>
	<label class="label" for="pin">Или 6 цифр</label>
	<input
		id="pin"
		class="input pin num"
		inputmode="numeric"
		autocomplete="one-time-code"
		maxlength="7"
		placeholder="000 000"
		bind:value={pin}
	/>
	{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	<Button variant="primary" type="submit" loading={busy} disabled={digits.length !== 6}
		>Войти</Button
	>
</form>
{#if scanOpen}
	{#await import('$lib/auth/QrScanner.svelte') then m}<m.default bind:open={scanOpen} />{/await}
{/if}

<style>
	.code-login {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.how {
		margin: 0;
		padding-left: 20px;
		color: var(--text-2);
		font-size: 14.5px;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.pin {
		font-size: 28px;
		letter-spacing: 0.2em;
		text-align: center;
		height: 60px;
	}
</style>
