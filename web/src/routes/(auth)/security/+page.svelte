<script lang="ts">
	import { goto } from '$app/navigation';
	import { onMount } from 'svelte';
	import { encode } from 'uqr';
	import { post } from '$lib/api';
	import { loadMe, session } from '$lib/session.svelte';
	import Button from '$lib/ui/Button.svelte';
	import PasswordFields from '$lib/auth/PasswordFields.svelte';

	let password = $state('');
	let confirm = $state('');
	let code = $state('');
	let error = $state('');
	let busy = $state(false);
	let secret = $state('');
	let uri = $state('');

	const restriction = $derived(session.me?.restriction);

	const qr = $derived.by(() => {
		if (!uri) return null;
		const { data } = encode(uri, { ecc: 'M' });
		let path = '';
		data.forEach((row, y) =>
			row.forEach((on, x) => {
				if (on) path += `M${x} ${y}h1v1h-1z`;
			})
		);
		return { size: data.length, path };
	});

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
				await post('/api/me/totp/enable', { code });
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

{#if restriction === 'password_change_required'}
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
		{#if qr}
			<svg class="qr" viewBox="-2 -2 {qr.size + 4} {qr.size + 4}" role="img" aria-label="QR-код">
				<rect x="-2" y="-2" width={qr.size + 4} height={qr.size + 4} fill="#fff" />
				<path d={qr.path} fill="#111" />
			</svg>
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
		height: 200px;
		align-self: center;
		border-radius: var(--r);
	}
	code {
		word-break: break-all;
	}
</style>
