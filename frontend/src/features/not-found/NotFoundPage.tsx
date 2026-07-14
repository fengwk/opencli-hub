import { Link } from 'react-router-dom'
import { Empty } from '@/shared/components'

export function NotFoundPage() {
  return (
    <section className="page">
      <header className="page-header">
        <h1 className="page-title">404</h1>
      </header>
      <Empty
        title="页面不存在"
        description="请求的页面未找到。"
        action={
          <Link className="btn btn-primary" to="/instances">
            返回实例管理
          </Link>
        }
      />
    </section>
  )
}
