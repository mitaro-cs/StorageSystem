<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { Check, History } from '@lucide/svelte';
	import { putFile } from '$lib/upload';
	import { waitForRestart } from '$lib/settings/server/restart';
	import { post } from '$lib/api';
	import Button from '$lib/ui/Button.svelte';
	import PasswordFields from '$lib/auth/PasswordFields.svelte';
	import FioField from '$lib/auth/FioField.svelte';
	import { fioError, suggestUsername } from '$lib/names';

	// Сервер печатает ссылку с кодом (а установщик сам её открывает) — вводить его не нужно.
	const fromLink = page.url.searchParams.get('code');
	let code = $state(fromLink ?? '');
	let mode = $state<'single' | 'multi'>('single');
	let groupName = $state('');
	let university = $state('МТУСИ');
	let course = $state<number | null>(1);
	let instanceName = $state('');
	let displayName = $state('');
	let username = $state('');
	let usernameTouched = $state(false);
	$effect(() => {
		if (!usernameTouched) username = suggestUsername(displayName);
	});
	const UNIVERSITIES = ['МТУСИ', 'МИРЭА', 'МЭИ', 'МАИ', 'Бауманка'];
	let otherUni = $state(false);
	let password = $state('');
	let confirm = $state('');
	let error = $state('');
	let busy = $state(false);
	let restoring = $state<'' | 'restarting' | 'staged'>('');
	let restoreMessage = $state('');
	let backupFile: HTMLInputElement | undefined = $state();

	/** Переезд на новый компьютер: вместо настройки — всё из резервной копии. */
	async function restore(f: File) {
		error = '';
		if (!code.trim()) {
			error = 'Нужен код настройки — откройте ссылку из окна сервера';
			return;
		}
		busy = true;
		try {
			const r = await putFile<{ status: 'restarting' | 'staged'; message: string }>(
				`/api/setup/restore?code=${encodeURIComponent(code.trim())}`,
				f
			);
			restoring = r.status;
			restoreMessage = r.message;
			if (r.status === 'restarting') await waitForRestart();
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
			if (backupFile) backupFile.value = '';
		}
	}

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		error = fioError(displayName) || (password !== confirm ? 'Пароли не совпадают' : '');
		if (error) return;
		busy = true;
		try {
			await post(
				'/api/setup',
				{
					code,
					mode,
					instanceName,
					group: { name: groupName, university, course },
					username,
					displayName,
					password
				},
				{ anonymous: true }
			);
			await goto('/', { replaceState: true, invalidateAll: true });
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}
</script>

<svelte:head><title>Первый запуск · groupbase</title></svelte:head>

<h1>Первый запуск</h1>
<p class="muted">
	Создадим группу и ваш аккаунт: вы станете администратором сайта и старостой группы. Роль старосты
	потом можно передать в «Участниках».
</p>

{#if restoring}
	<div class="restoring" role="status">
		{#if restoring === 'restarting'}<span class="spinner" aria-hidden="true"></span>{/if}
		<p>{restoreMessage}</p>
	</div>
{:else}
	<form onsubmit={submit}>
		{#if fromLink}
			<p class="linked"><Check size={16} /> Код настройки подставлен из ссылки</p>
		{:else}
			<div>
				<label class="label" for="code">Код настройки</label>
				<input id="code" class="input num" bind:value={code} autocomplete="off" required />
				<p class="hint">
					Проще открыть ссылку из окна сервера — в ней код уже есть. Или выполните <code
						>groupbase init</code
					>.
				</p>
			</div>
		{/if}

		<fieldset class="modes">
			<legend class="label">Режим</legend>
			<label class="mode" class:on={mode === 'single'}>
				<input type="radio" bind:group={mode} value="single" />
				<strong>Одна группа</strong><span class="faint small">Сайт одной группы</span>
			</label>
			<label class="mode" class:on={mode === 'multi'}>
				<input type="radio" bind:group={mode} value="multi" />
				<strong>Несколько групп</strong><span class="faint small">Поток или кафедра</span>
			</label>
		</fieldset>

		{#if mode === 'multi'}
			<div>
				<label class="label" for="iname">Название инстанса</label>
				<input id="iname" class="input" bind:value={instanceName} placeholder="Поток БИН-25" />
			</div>
		{/if}

		<div>
			<label class="label" for="gname">{mode === 'multi' ? 'Первая группа' : 'Группа'}</label>
			<input id="gname" class="input" bind:value={groupName} placeholder="БИН2509" required />
		</div>
		<fieldset class="pick">
			<legend class="label">Курс</legend>
			<div class="chips" role="radiogroup" aria-label="Курс">
				{#each [1, 2, 3, 4, 5, 6] as n (n)}
					<button
						type="button"
						role="radio"
						aria-checked={course === n}
						class:on={course === n}
						onclick={() => (course = n)}>{n}</button
					>
				{/each}
			</div>
		</fieldset>
		<fieldset class="pick">
			<legend class="label">Вуз</legend>
			<div class="chips" role="radiogroup" aria-label="Вуз">
				{#each UNIVERSITIES as u (u)}
					<button
						type="button"
						role="radio"
						aria-checked={!otherUni && university === u}
						class:on={!otherUni && university === u}
						onclick={() => ((university = u), (otherUni = false))}>{u}</button
					>
				{/each}
				<button
					type="button"
					role="radio"
					aria-checked={otherUni}
					class:on={otherUni}
					onclick={() => ((otherUni = true), (university = ''))}>Другой</button
				>
			</div>
			{#if otherUni}
				<input
					class="input other"
					bind:value={university}
					placeholder="Название вуза"
					aria-label="Вуз"
				/>
			{/if}
		</fieldset>

		<hr />

		<FioField bind:value={displayName} />
		<div>
			<label class="label" for="uname">Имя пользователя для входа</label>
			<input
				id="uname"
				class="input"
				bind:value={username}
				autocomplete="username"
				autocapitalize="none"
				spellcheck="false"
				placeholder="ivanov.ivan"
				oninput={() => (usernameTouched = true)}
				required
			/>
			<p class="hint">Придумали по ФИО — можно поменять.</p>
		</div>
		<PasswordFields bind:password bind:confirm />

		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
		<Button variant="primary" type="submit" loading={busy}>Создать</Button>
	</form>

	<div class="restore">
		<p class="muted small">Переезжаете на новый компьютер или переустановили программу?</p>
		<input
			bind:this={backupFile}
			type="file"
			accept=".zip,application/zip"
			class="sr-only"
			onchange={(e) => e.currentTarget.files?.[0] && restore(e.currentTarget.files[0])}
		/>
		<Button variant="ghost" onclick={() => backupFile?.click()} loading={busy}
			><History size={16} /> Восстановить из резервной копии</Button
		>
	</div>
{/if}

<style>
	.restore {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 8px;
		margin-top: var(--s5);
		padding-top: var(--s4);
		border-top: 1px solid var(--border);
	}
	.restoring {
		display: flex;
		align-items: center;
		gap: var(--s3);
		padding: var(--s4);
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.spinner {
		flex: none;
		width: 22px;
		height: 22px;
		border: 3px solid var(--border);
		border-top-color: var(--text);
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}
	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
	.modes {
		border: 0;
		padding: 0;
		margin: 0;
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: var(--s2);
	}
	.modes legend {
		grid-column: 1 / -1;
	}
	.mode {
		display: flex;
		flex-direction: column;
		gap: 2px;
		padding: 12px;
		border: 1px solid var(--border-strong);
		border-radius: var(--r);
		cursor: pointer;
		transition:
			border-color var(--dur) var(--ease),
			background-color var(--dur) var(--ease);
	}
	.mode.on {
		border-color: var(--accent);
		background: var(--accent-soft);
	}
	.mode input {
		position: absolute;
		opacity: 0;
	}
	.mode:has(input:focus-visible) {
		outline: 2px solid var(--focus);
		outline-offset: 2px;
	}
	.linked {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 10px 14px;
		border-radius: var(--r);
		background: var(--ok-soft);
		color: var(--ok);
		font-weight: 550;
	}
	.pick {
		border: 0;
		padding: 0;
		margin: 0;
	}
	.pick legend {
		padding: 0;
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
	}
	.chips button {
		min-width: 44px;
		height: 40px;
		padding: 0 14px;
		border: 1px solid var(--border-strong);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text);
		font-weight: 550;
	}
	.chips button.on {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	.other {
		margin-top: 8px;
	}
	hr {
		border: 0;
		border-top: 1px solid var(--border);
		margin: 4px 0;
	}
</style>
