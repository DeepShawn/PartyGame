import { useEffect, useState } from 'react';
import { adminSignOut, supabase } from '../services/leaderboard';
import { Button, Card, SectionTitle, StatusNote, TextField } from './Ui';

export function AuthView() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [user, setUser] = useState<string | null>(null);
  const [message, setMessage] = useState('');
  useEffect(() => {
    void supabase.auth.getUser().then(({ data }) => setUser(data.user?.email ?? null));
    const { data } = supabase.auth.onAuthStateChange((_event, session) => setUser(session?.user.email ?? null));
    return () => data.subscription.unsubscribe();
  }, []);
  const signIn = async () => {
    setMessage('');
    const { error } = await supabase.auth.signInWithPassword({ email: email.trim(), password });
    setMessage(error ? error.message : '登录成功');
  };
  const signUp = async () => {
    setMessage('');
    const { error } = await supabase.auth.signUp({ email: email.trim(), password });
    setMessage(error ? error.message : '注册成功，请按项目邮件确认设置完成登录。');
  };
  return (
    <div className="page">
      <SectionTitle title="账号与成绩" subtitle="登录后记录你的对局成绩、查看跨设备排行榜；普通记录自动保存，异常记录进入审核。" />
      <Card>
        {user ? (
          <><p>当前账号：<strong>{user}</strong></p><Button onClick={() => void adminSignOut()}>退出登录</Button></>
        ) : (
          <>
            <TextField label="邮箱" value={email} onChange={setEmail} />
            <TextField label="密码" value={password} onChange={setPassword} password />
            <div className="action-row"><Button variant="primary" onClick={() => void signIn()}>登录</Button><Button onClick={() => void signUp()}>注册</Button></div>
          </>
        )}
        {message ? <StatusNote>{message}</StatusNote> : null}
      </Card>
    </div>
  );
}
