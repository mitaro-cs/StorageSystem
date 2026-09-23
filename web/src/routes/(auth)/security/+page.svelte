<script lang="ts">
	import { goto } from '$app/navigation';
	import { onMount } from 'svelte';
	import { post } from '$lib/api';
	import { loadMe, session } from '$lib/session.svelte';
	import Button from '$lib/ui/Button.svelte';
	import PasswordFields from '$lib/auth/PasswordFields.svelte';
	import RecoveryCodes from '$lib/auth/RecoveryCodes.svelte';
	import QrCode from '$lib/ui/QrCode.svelte';

	let password = $state('');
	let confirm = $state('');
	let code = $state('');
	let error = $state('');
	let busy = $state(false);
	let secret = $state('');
	let uri = $state('');
	let recoveryCodes = $state<string[] | null>(null);

	const restriction = $derived(session.me?.restriction);

	onMount(async () => {
		try {
			await loadMe();
		} catch {
			return;
		}
		if (!session.me?.restriction) goto('/', { replaceState: true });
		else if (session.me.restriction === 'totp_setup_required') await startTotp();
	});

	const groupKey = (s: string) => s.match(/.{1,4}/g)?.join(' ') ?? s;

	async function startTotp() {
		const r = await post<{ secret: string; uri: string }>('/api/me/totp/setup');
		secret = r.secret;
		uri = r.uri;
	}

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		busy = true;
		try {
			if (restriction === 'password_change_required') {
				if (password !== confirm) throw new Error('Пароли не совпадают');
				await post('/api/me/password', { password });
			} else {
				const r = await post<{ recoveryCodes: string[] }>('/api/me/totp/enable', { code });
				recoveryCodes = r.recoveryCodes;
				return;
			}
			const me = await loadMe();
			if (me.restriction === 'totp_setup_required') {
				password = '';
				confirm = '';
				await startTotp();
			} else {
				await goto('/', { replaceState: true, invalidateAll: true });
			}
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}
</script>

<svelte:head><title>Безопасность · groupbase</title></svelte:head>

{#if recoveryCodes}
	<h1>Сохраните резервные коды</h1>
	<p class="muted">Двухфакторная защита включена. Эти коды выручат, если телефон потеряется.</p>
	<RecoveryCodes
		codes={recoveryCodes}
		doneLabel="Продолжить"
		ondone={() => goto('/', { replaceState: true, invalidateAll: true })}
	/>
{:else if restriction === 'password_change_required'}
	<h1>Смените пароль</h1>
	<p class="muted">Вы вошли по временному паролю. Придумайте свой — его будете знать только вы.</p>
	<form onsubmit={submit}>
		<PasswordFields bind:password bind:confirm />
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
		<Button variant="primary" type="submit" loading={busy}>Сохранить</Button>
	</form>
{:else if restriction === 'totp_setup_required'}
	<h1>Двухфакторная защита</h1>
	<p class="muted">
		Для администраторов и модераторов она обязательна. Отсканируйте код в приложении (Яндекс Ключ,
		Google Authenticator, Aegis) и введите 6 цифр.
	</p>
	<form onsubmit={submit}>
		{#if uri}
			<div class="qr"><QrCode value={uri} label="QR-код" /></div>
			<p class="hint">
				Не сканируется? Введите ключ вручную: <code class="num">{groupKey(secret)}</code>. Если
				запись groupbase уже была в приложении — удалите её и добавьте заново.
			</p>
		{/if}
		<div>
			<label class="label" for="code">Код из приложения</label>
			<input
				id="code"
				class="input num"
				inputmode="numeric"
				autocomplete="one-time-code"
				bind:value={code}
				required
			/>
		</div>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
		<Button variant="primary" type="submit" loading={busy}>Включить</Button>
	</form>
{:else}
	<p class="faint">Загрузка…</p>
{/if}

<style>
	.qr {
		width: 200px;
		align-self: center;
	}
	code {
		word-break: break-all;
	}
</style>
