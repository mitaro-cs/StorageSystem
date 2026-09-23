<script lang="ts">
	import { goto } from '$app/navigation';
	import { post } from '$lib/api';
	import Button from '$lib/ui/Button.svelte';
	import PasswordFields from '$lib/auth/PasswordFields.svelte';
	import FioField from '$lib/auth/FioField.svelte';
	import { fioError } from '$lib/names';

	let code = $state('');
	let mode = $state<'single' | 'multi'>('single');
	let groupName = $state('');
	let university = $state('МТУСИ');
	let course = $state<number | null>(1);
	let instanceName = $state('');
	let displayName = $state('');
	let username = $state('');
	let password = $state('');
	let confirm = $state('');
	let error = $state('');
	let busy = $state(false);

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

<form onsubmit={submit}>
	<div>
		<label class="label" for="code">Код настройки</label>
		<input id="code" class="input num" bind:value={code} autocomplete="off" required />
		<p class="hint">
			Он напечатан в логе сервера при запуске. Можно вместо этого выполнить <code
				>groupbase init</code
			>.
		</p>
	</div>

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

	<div class="grid">
		<div>
			<label class="label" for="gname">{mode === 'multi' ? 'Первая группа' : 'Группа'}</label>
			<input id="gname" class="input" bind:value={groupName} placeholder="БИН2509" required />
		</div>
		<div>
			<label class="label" for="course">Курс</label>
			<input id="course" class="input num" type="number" min="1" max="6" bind:value={course} />
		</div>
	</div>
	<div>
		<label class="label" for="uni">Вуз</label>
		<input id="uni" class="input" bind:value={university} />
	</div>

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
			required
		/>
	</div>
	<PasswordFields bind:password bind:confirm />

	{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	<Button variant="primary" type="submit" loading={busy}>Создать</Button>
</form>

<style>
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
	.grid {
		display: grid;
		grid-template-columns: 1fr 96px;
		gap: var(--s3);
	}
	hr {
		border: 0;
		border-top: 1px solid var(--border);
		margin: 4px 0;
	}
</style>
